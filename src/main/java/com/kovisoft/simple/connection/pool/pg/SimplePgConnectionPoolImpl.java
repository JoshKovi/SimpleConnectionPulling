package com.kovisoft.simple.connection.pool.pg;

import com.kovisoft.logger.exports.Logger;
import com.kovisoft.logger.exports.LoggerFactory;
import com.kovisoft.simple.connection.pool.exports.ConnectionWrapper;
import com.kovisoft.simple.connection.pool.exports.PoolConfig;
import com.kovisoft.simple.connection.pool.exports.SimplePgConnectionPool;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;


public class SimplePgConnectionPoolImpl implements SimplePgConnectionPool, AutoCloseable {

    protected final Logger logger = LoggerFactory.createLogger(System.getProperty("user.dir") + "/logs", "DB_pool_");
    private final static String GET_CONN_STATE = "SELECT state FROM pg_stat_activity WHERE pid = ?";
    private ConnectionWrapperImpl managerConnection;
    private final BlockingQueue<ConnectionWrapperImpl> connections;
    private final Set<ConnectionWrapperImpl> cws = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final int maxCharacters;
    private final int maxCachedStatements;
    private final int minConnections;
    private final int maxConnections;
    private final int connectionLifeSpan;
    private final int requestsPerMinutePerCon;


    private final String connectionUrl;
    private final String user;
    private final String pass;
    private final Map<String, String> prepStatements = new ConcurrentHashMap<>();
    private final Map<String, Integer> constStatements = new ConcurrentHashMap<>();


    private final ScheduledExecutorService poolManagementThread;
    private final ScheduledExecutorService validationExecutor;
    private volatile boolean running = true;
    private int requestsPastMinute = 0;
    private long minuteStart = System.currentTimeMillis();
    private static final long MILLIS_PER_MINUTE = 60000;
    private volatile int targetConnections;

    public SimplePgConnectionPoolImpl(PoolConfig config) throws SQLException {
        this.minConnections = config.getMinConnections();
        this.maxConnections = config.getMaxConnections();
        this.targetConnections =  Math.max((this.maxConnections - this.minConnections) / 2, minConnections);
        this.requestsPerMinutePerCon = config.getRequestsPerMinutePerConn();
        this.connectionLifeSpan = config.getConnectionLifeSpan();
        this.maxCharacters = config.getMaxCharacters();
        this.maxCachedStatements = config.getMaxCachedStatements();
        connections = new LinkedBlockingQueue<>(maxConnections + 1);

        this.connectionUrl = config.getUrl();
        this.user = config.getUser();
        this.pass = config.getPass();
        managerConnection = new ConnectionWrapperImpl(connectionUrl, user, pass,
                connectionLifeSpan * 2, Map.of(GET_CONN_STATE, GET_CONN_STATE));

        for(int i = 0; i < targetConnections; i++){initConnAndAddToPool();}
        managePool();
        logger.info(String.format("Connection was setup minCon: %d, maxCon: %d, target %s, "
                + "rpmpc: %d, lifeSpan: %d, chars: %d, cache: %d, url: %s", minConnections, maxConnections,
                targetConnections, requestsPerMinutePerCon, connectionLifeSpan, maxCharacters, maxCachedStatements,
                connectionUrl

        ));
        poolManagementThread = Executors.newScheduledThreadPool(1);
        poolManagementThread.scheduleWithFixedDelay(this::managePool, 0,
                config.getConnectionCheckIntervals(), TimeUnit.MILLISECONDS);

        validationExecutor = Executors.newScheduledThreadPool(1);
        validationExecutor.scheduleWithFixedDelay(this::validateConnections, 5, 5, TimeUnit.MINUTES);

        logger.info("Pool Setup without exception!");

    }

    public SimplePgConnectionPoolImpl(PoolConfig config, Map<String, String> prepStatements) throws SQLException {
        this(config);
        addPreparedStatementsToPool(prepStatements);
        managePool();
        logger.info("Default Prepared statements added to pool without exception!");
    }

    public SimplePgConnectionPoolImpl(PoolConfig config, Map<String, String> prepStatements,
                                      Map<String, Integer> constStatements) throws SQLException {
        this(config);
        addPreparedStatementsToPool(prepStatements, constStatements);
        managePool();
        logger.info("Default Prepared statements added to pool without exception!");
    }

    @Override
    public ConnectionWrapper borrowConnection() throws SQLException, InterruptedException {
        return borrowConnection(50);
    }

    @Override
    public ConnectionWrapper borrowConnection(long millis) throws SQLException, InterruptedException {
        requestsPastMinute++;
        logger.info("Connection borrow requested!");
        ConnectionWrapperImpl cw = connections.poll(millis, TimeUnit.MILLISECONDS);
        logger.info("Connection 1st pool complete.");
        if(cw != null){
            return cw;
        }
        logger.info("First pool occupied try to get next!");
        while(!connections.isEmpty()){
            cw = connections.poll(millis, TimeUnit.MILLISECONDS);
            if(cw != null){
                return cw;
            }
        }
        logger.warn("Connections appear to be in use? All of them?! I don't but it."
         + "Connection Wrapper Set Size: " + cws.size() + ", Connections Empty");
        throw new SQLException("All the connections were either occupied or interupted!");
    }

    @Override
    public void shutDownPool() throws Exception {
        running = false;
        close();
    }

    @Override
    public int addPreparedStatementsToPool(Map<String, String> prepStmts) {
        return addPreparedStatementsToPool(prepStmts, null);
    }

    @Override
    public int addPreparedStatementsToPool(Map<String, String> prepStmts, Map<String, Integer> statmentConstMap) {
        int priorToAdd = prepStatements.size();
        boolean notNull = statmentConstMap != null;
        prepStmts.forEach((key, value) ->{
            if(value == null || value.length() > maxCharacters
                    || prepStatements.size() >= maxCachedStatements) return;
            this.prepStatements.put(key, value);
            if(notNull && statmentConstMap.containsKey(key)){
                this.constStatements.put(key, statmentConstMap.get(key));
            }
        });
        return prepStatements.size() - priorToAdd;
    }



    private void initConnAndAddToPool() throws SQLException {
        if(prepStatements.isEmpty() && constStatements.isEmpty()){
            cws.add(new ConnectionWrapperImpl(connectionUrl, user, pass, connectionLifeSpan));
        } else if (constStatements.isEmpty()){
            cws.add(new ConnectionWrapperImpl(connectionUrl, user, pass,
                    connectionLifeSpan, prepStatements));
        } else {
            cws.add(new ConnectionWrapperImpl(connectionUrl, user, pass,
                    connectionLifeSpan, prepStatements, constStatements));
        }
    }

    private void validateConnections(){
        for(ConnectionWrapperImpl cw : cws){
            try{
                if(!cw.validate()) cw.close();
            } catch(Exception e){
                logger.except("Exception occurred during validation of thread.");
                cw = null;
            }
        }
    }

    private void managePool(){
        if(!running){
            Thread.currentThread().interrupt();
            return;
        }
        try{
            if(System.currentTimeMillis() - minuteStart >= MILLIS_PER_MINUTE){
                adjustPoolSize();
            }
            manageConnections();
        } catch (SQLException | InterruptedException e) {
            logger.warn("Exception occurred during regular pool management, this may be a one off or a problem", e);
        }

    }

    private void adjustPoolSize(){
        minuteStart = System.currentTimeMillis();
        if(requestsPastMinute <= requestsPerMinutePerCon){
            logger.info("Low traffic, setting connection pool to minimum of: " + minConnections);
            targetConnections = minConnections;
        } else if(requestsPastMinute > requestsPerMinutePerCon * maxConnections){
            logger.warn(String.format("It looks like your requests per minute (%d) "
                    +"exceed your desired capacity of %d requests per connection per minute "
                    +"on a maximum of %d connections. Sounds like one of those good problems!",
                    requestsPastMinute, requestsPerMinutePerCon, maxConnections));
            targetConnections = maxConnections;
        } else {
            targetConnections =  Math.max((int) Math.ceil((double) requestsPastMinute / requestsPerMinutePerCon),
                    minConnections);
            logger.info(String.format("Adjusting connections during medium traffic expectations to %d connections",
                    targetConnections));
        }
        requestsPastMinute = 0;
    }



    private void manageConnections() throws SQLException, InterruptedException {
        Iterator<ConnectionWrapperImpl> iterator = cws.iterator();
        while (iterator.hasNext()){
            ConnectionWrapperImpl cw = iterator.next();
            if(cw == null || cw.isClosed() || cw.hasExpired()){
                removeConnection(cw, iterator);
                continue;
            }
            if(cw.notReadyForReplacement() && prepStatements.size() > cw.countStatements()){
                cw.addPreparedStatements(prepStatements);
            }
        }
        if(cws.size() < targetConnections){
            int genCount = targetConnections - cws.size();
            for(int i = 0; i < genCount; i++){
                initConnAndAddToPool();
            }
        } else if(cws.size() > targetConnections){
            int remove = cws.size() - targetConnections;
            cws.stream().sorted(Comparator.comparing(ConnectionWrapperImpl::getExpiration))
                    .limit(remove)
                    .toList()
                    .forEach(cws::remove);
        }
        if(managerConnection.isClosed() || managerConnection.hasExpired()){
            managerConnection = new ConnectionWrapperImpl(connectionUrl, user, pass,
                    connectionLifeSpan * 2, Map.of(GET_CONN_STATE,GET_CONN_STATE));
        }

        for(ConnectionWrapperImpl cw : cws){
            if(!connections.contains(cw) && (!cw.inUse() || queryPid(cw))){
                connections.put(cw);
            }
        }
    }

    private boolean queryPid(ConnectionWrapperImpl cw)  {
        try{
            PreparedStatement pStmt = managerConnection.getConnection().prepareStatement(GET_CONN_STATE);
            pStmt.setInt(1, cw.getPid());
            ResultSet rs = pStmt.executeQuery();
            if(rs.next()){
                String state = rs.getString("state");
                if("idle".equalsIgnoreCase(state)){
                    //TODO: Evaluate if this makes sense, im checking connections by default every half second or so
                    // and if they are idle I release them, basically if they aren't in a transaction throw them back
                    // but what if a process takes a bit longer to start... maybe im over thinking this...
                    cw.release();
                    return true;
                }
                if(state == null){
                    cw.close();
                }
            } else {
                cw.close();
            }
        } catch (SQLException e) {
            logger.except("It appears something went wrong with the query checking pid!", e);
        } catch (Exception e) {
            logger.except("It appears we are having some trouble shutting down a thread at pid: " + cw.getPid(), e);
        }
        return false;
    }



    private void removeConnection(ConnectionWrapperImpl cw, Iterator<ConnectionWrapperImpl> iterator){
        try{
            if(cw != null) {cw.close();}

            iterator.remove();
        } catch (Exception e) {
            iterator.remove();
        }
    }

    private void removeConnection(ConnectionWrapperImpl cw){
        try{
            if(cw != null) {cw.close();}
            cw.close();
            cws.remove(cw);
        } catch (Exception e) {
            cws.remove(cw);
        }
    }



    @Override
    public void close() throws Exception {
        logger.info("Closing connection pool!");
        running = false;
        connections.clear();
        Exception lastException = null;
        for(ConnectionWrapperImpl cw : cws){
            try{
                if(cw != null) {cw.close();}
            } catch (Exception e){
                logger.except("Exception thrown trying to close connection from Pool close operation.", e);
                cw = null;
                lastException = e;
            }
        }
        try{
            poolManagementThread.close();
        } catch (Exception e){
            logger.except("Exception thrown trying to close pool manager connection from Pool close operation.", e);
            lastException = e;
        }
        try{
            validationExecutor.close();
        } catch (Exception e){
            logger.except("Exception thrown trying to close validationExecutor from Pool close operation.", e);
            lastException = e;
        }
        if(lastException != null) throw lastException;
    }
}
