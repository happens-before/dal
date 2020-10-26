package com.ctrip.platform.dal.dao.datasource.cluster;

import com.ctrip.framework.dal.cluster.client.util.CaseInsensitiveProperties;
import com.ctrip.platform.dal.dao.helper.DalElementFactory;
import com.ctrip.platform.dal.dao.log.ILogger;
import com.ctrip.platform.dal.exceptions.DalException;
import com.ctrip.platform.dal.exceptions.DalRuntimeException;
import com.ctrip.platform.dal.exceptions.InvalidConnectionException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public class OrderedAccessStrategy implements RouteStrategy{

    private static final ILogger LOGGER = DalElementFactory.DEFAULT.getILogger();
    private static final String CAT_LOG_TYPE = "DAL.pickConnection";
    private static final String VALIDATE_FAILED = "Router::validateFailed";
    private static final String ROUTER_INITIALIZE = "Router::initialize";
    private static final String ROUTER_ORDER_HOSTS = "Router::cluster:%s";
    private static final String NO_HOST_AVAILABLE = "Router::noHostAvailable:%s";
    private static final String INITIALIZE_MSG = "configuredHosts:%s;strategyOptions:%s";
    private static final String ORDER_HOSTS = "orderHosts:%s";
    private static final String CONNECTION_HOST_CHANGE = "Router::connectionHostChange:%s";
    private static final String CHANGE_FROM_TO = "change from %s to %s";

    private ConnectionValidator connectionValidator;
    private HostValidator hostValidator;
    private Set<HostSpec> configuredHosts;
    private ConnectionFactory connFactory;
    private CaseInsensitiveProperties strategyOptions;
    private List<HostSpec> orderHosts;
    private volatile HostSpec currentHost;
    private String cluster = "";
    private String status; // birth --> init --> destroy

    private enum RouteStrategyStatus {
        birth, init, destroy;
    }

    public OrderedAccessStrategy() {
        status = RouteStrategyStatus.birth.name();
    }

    @Override
    public Connection pickConnection(RequestContext context) throws SQLException {
        isInit();
        for (int i = 0; i < configuredHosts.size(); i++) {
            HostSpec targetHost = null;
            try {
                targetHost = pickHost();
                synchronized (currentHost) {
                    if (!targetHost.equals(currentHost)) {
                        LOGGER.warn(String.format(CONNECTION_HOST_CHANGE, String.format(CHANGE_FROM_TO, currentHost.toString(), targetHost.toString())));
                        LOGGER.logEvent(CAT_LOG_TYPE, String.format(CONNECTION_HOST_CHANGE, cluster), String.format(CHANGE_FROM_TO, currentHost.toString(), targetHost.toString()));
                        currentHost = targetHost;
                    }
                }
                Connection targetConnection = connFactory.getPooledConnectionForHost(targetHost);

                return targetConnection;
            } catch (InvalidConnectionException e) {
                if (targetHost != null){
                    LOGGER.warn(VALIDATE_FAILED + targetHost.toString());
                    LOGGER.logEvent(CAT_LOG_TYPE, VALIDATE_FAILED, targetHost.toString());
                }
            } finally {
                hostValidator.triggerValidate();
            }
        }

        throw new DalException(NO_HOST_AVAILABLE);
    }

    @Override
    public void initialize(ShardMeta shardMeta, ConnectionFactory connFactory, CaseInsensitiveProperties strategyProperties) {
        isDestroy();
        this.status = RouteStrategyStatus.init.name();
        this.configuredHosts = shardMeta.configuredHosts();
        this.connFactory = connFactory;
        this.strategyOptions = strategyProperties;
        buildValidator();
        buildOrderHosts();
        this.currentHost = orderHosts.get(0);
        LOGGER.info(ROUTER_INITIALIZE + ":" + String.format(INITIALIZE_MSG, configuredHosts.toString(), strategyOptions.toString()));
        LOGGER.logEvent(CAT_LOG_TYPE, ROUTER_INITIALIZE, String.format(INITIALIZE_MSG, configuredHosts.toString(), strategyOptions.toString()));
    }

    private void buildOrderHosts () {
        //TODO clusterName to be
        List<String> zoneOrder = strategyOptions.getStringList("zonesPriority", ",", null);
        ZonedHostSorter sorter = new ZonedHostSorter(zoneOrder);
        this.orderHosts = sorter.sort(configuredHosts);
        this.cluster = "cluster";
        LOGGER.info(ROUTER_ORDER_HOSTS + ":" + String.format(ORDER_HOSTS, orderHosts.toString()));
        LOGGER.logEvent(CAT_LOG_TYPE, String.format(ROUTER_ORDER_HOSTS, cluster), String.format(ORDER_HOSTS, orderHosts.toString()));
    }

    private void buildValidator() {
        long failOverTime = strategyOptions.getLong("failoverTimeMS", 0);
        long blackListTimeOut = strategyOptions.getLong("blacklistTimeoutMS", 0);
        MajorityHostValidator validator = new MajorityHostValidator(connFactory, configuredHosts, failOverTime, blackListTimeOut);
        this.connectionValidator = validator;
        this.hostValidator = validator;
    }

    private void isInit() {
        if (!RouteStrategyStatus.init.name().equalsIgnoreCase(status))
            throw new DalRuntimeException("OrderedAccessStrategy is not ready, status: " + this.status);
    }

    private void isDestroy () {
        if (RouteStrategyStatus.init.name().equalsIgnoreCase(status))
            throw new DalRuntimeException("OrderedAccessStrategy has been init, status: " + this.status);
    }

    @Override
    public ConnectionValidator getConnectionValidator(){
        isInit();
        return connectionValidator;
    }

    @Override
    public void destroy() {
        isInit();
        this.status = RouteStrategyStatus.destroy.name();
    }

    private HostSpec pickHost() throws DalException {
        for (HostSpec hostSpec : orderHosts) {
            if (hostValidator.available(hostSpec)) {
                return hostSpec;
            }
        }

        throw new DalException(String.format(NO_HOST_AVAILABLE, orderHosts.toString()));
    }
}