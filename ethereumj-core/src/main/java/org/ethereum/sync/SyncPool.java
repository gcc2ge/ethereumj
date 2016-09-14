package org.ethereum.sync;

import org.ethereum.config.SystemProperties;
import org.ethereum.core.Blockchain;
import org.ethereum.listener.EthereumListener;
import org.ethereum.net.rlpx.discover.NodeHandler;
import org.ethereum.net.rlpx.discover.NodeManager;
import org.ethereum.net.server.Channel;
import org.ethereum.net.server.ChannelManager;
import org.ethereum.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.min;
import static org.ethereum.util.BIUtil.isIn20PercentRange;

/**
 * <p>Encapsulates logic which manages peers involved in blockchain sync</p>
 *
 * Holds connections, bans, disconnects and other peers logic<br>
 * The pool is completely threadsafe<br>
 * Implements {@link Iterable} and can be used in "foreach" loop<br>
 * Used by {@link SyncManager}
 *
 *  SyncPool用来提供和管理网络节点(activePeers)
 *
 * SyncPool用来提供和管理网络节点，它在初始化的过程中会创建一个网络连接维护线程。区块同步有可能需要很长时间，因此，为了加快同步，应该做好以下几点：
     首先，需要与充分多的网络节点保持连接，理由很简单，就是要防止单点故障，最大的连接数量是可以配置的。
     其次，同步过程需要尽量只与一些网络质量好的节点连接，例如网络延时小的节点。
     另外，同步过程还需要尽量只与一些发送的区块的质量较高的节点连接，也就是说发送的区块的难度越大越好。
    网络连接维护线程的目的就是要把这几件事做好，它主要是通过对连接上的每一个节点进行网络质量和区块质量统计来实现的，具体逻辑并不复杂。
 * @author Mikhail Kalinin
 * @since 10.08.2015
 */
@Component
public class SyncPool {

    public static final Logger logger = LoggerFactory.getLogger("sync");

    private static final long WORKER_TIMEOUT = 3; // 3 seconds

    private final List<Channel> activePeers = Collections.synchronizedList(new ArrayList<Channel>());

    private BigInteger lowerUsefulDifficulty = BigInteger.ZERO;

    @Autowired
    private EthereumListener ethereumListener;

    @Autowired
    private Blockchain blockchain;

    @Autowired
    private SystemProperties config;

    @Autowired
    private NodeManager nodeManager;

    @Autowired
    private ChannelManager channelManager;
    private ScheduledExecutorService poolLoopExecutor = Executors.newSingleThreadScheduledExecutor();

    /**
     * 创建网络连接维护线程
     */
    @PostConstruct
    public void init() {

        if (!config.isSyncEnabled()) return;

        updateLowerUsefulDifficulty();

        poolLoopExecutor.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            heartBeat();
                            updateLowerUsefulDifficulty();
                            fillUp();
                            prepareActive();
                            cleanupActive();
                        } catch (Throwable t) {
                            logger.error("Unhandled exception", t);
                        }
                    }
                }, WORKER_TIMEOUT, WORKER_TIMEOUT, TimeUnit.SECONDS
        );
    }

    public void close() {
        try {
            poolLoopExecutor.shutdownNow();
        } catch (Exception e) {
            logger.warn("Problems shutting down executor", e);
        }
    }

    @Nullable
    public synchronized Channel getAnyIdle() {
        ArrayList<Channel> channels = new ArrayList<>(activePeers);
        Collections.shuffle(channels);
        for (Channel peer : channels) {
            if (peer.isIdle())
                return peer;
        }

        return null;
    }

    @Nullable
    public synchronized Channel getBestIdle() {
        for (Channel peer : activePeers) {
            if (peer.isIdle())
                return peer;
        }
        return null;
    }

    @Nullable
    public synchronized Channel getByNodeId(byte[] nodeId) {
        return channelManager.getActivePeer(nodeId);
    }

    public synchronized void onDisconnect(Channel peer) {
        if (activePeers.remove(peer)) {
            logger.info("Peer {}: disconnected", peer.getPeerIdShort());
        }
    }

    public synchronized Set<String> nodesInUse() {
        Set<String> ids = new HashSet<>();
        for (Channel peer : channelManager.getActivePeers()) {
            ids.add(peer.getPeerId());
        }
        return ids;
    }

    synchronized void logActivePeers() {
        if (logger.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("Peer stats:\n");
            sb.append("Active peers\n");
            sb.append("============\n");
            for (Channel peer : new ArrayList<>(activePeers)) sb.append(peer.logSyncStats()).append('\n');
            sb.append("Connected peers\n");
            sb.append("============\n");
            for (Channel peer : new ArrayList<>(channelManager.getActivePeers())) sb.append(peer.logSyncStats()).append('\n');
            logger.info(sb.toString());
        }
    }

    private void fillUp() {
        int lackSize = config.maxActivePeers() - channelManager.getActivePeers().size();
        if(lackSize <= 0) return;

        Set<String> nodesInUse = nodesInUse();

        List<NodeHandler> newNodes = nodeManager.getBestEthNodes(nodesInUse, lowerUsefulDifficulty, lackSize);
        if (lackSize > 0 && newNodes.isEmpty()) {
            newNodes = nodeManager.getBestEthNodes(nodesInUse, BigInteger.ZERO, lackSize);
        }

        if (logger.isTraceEnabled()) {
            logDiscoveredNodes(newNodes);
        }

        for(NodeHandler n : newNodes) {
            channelManager.connect(n.getNode());
        }
    }

    private synchronized void prepareActive() {
        List<Channel> active = new ArrayList<>(channelManager.getActivePeers());

        if (active.isEmpty()) return;

        // filtering by 20% from top difficulty
        Collections.sort(active, new Comparator<Channel>() {
            @Override
            public int compare(Channel c1, Channel c2) {
                return c2.getTotalDifficulty().compareTo(c1.getTotalDifficulty());
            }
        });

        BigInteger highestDifficulty = active.get(0).getTotalDifficulty();
        int thresholdIdx = min(config.syncPeerCount(), active.size()) - 1;

        for (int i = thresholdIdx; i >= 0; i--) {
            if (isIn20PercentRange(active.get(i).getTotalDifficulty(), highestDifficulty)) {
                thresholdIdx = i;
                break;
            }
        }

        List<Channel> filtered = active.subList(0, thresholdIdx + 1);

        // sorting by latency in asc order
        Collections.sort(filtered, new Comparator<Channel>() {
            @Override
            public int compare(Channel c1, Channel c2) {
                return Double.valueOf(c1.getPeerStats().getAvgLatency()).compareTo(c2.getPeerStats().getAvgLatency());
            }
        });

        for (Channel channel : filtered) {
            if (!activePeers.contains(channel)) {
                ethereumListener.onPeerAddedToSyncPool(channel);
            }
        }

        activePeers.clear();
        activePeers.addAll(filtered);
    }

    private synchronized void cleanupActive() {
        Iterator<Channel> iterator = activePeers.iterator();
        while (iterator.hasNext()) {
            Channel next = iterator.next();
            if (next.isDisconnected()) {
                logger.info("Removing peer " + next + " from active due to disconnect.");
                iterator.remove();
            }
        }
    }


    private void logDiscoveredNodes(List<NodeHandler> nodes) {
        StringBuilder sb = new StringBuilder();
        for(NodeHandler n : nodes) {
            sb.append(Utils.getNodeIdShort(Hex.toHexString(n.getNode().getId())));
            sb.append(", ");
        }
        if(sb.length() > 0) {
            sb.delete(sb.length() - 2, sb.length());
        }
        logger.trace(
                "Node list obtained from discovery: {}",
                nodes.size() > 0 ? sb.toString() : "empty"
        );
    }

    private void updateLowerUsefulDifficulty() {
        BigInteger td = blockchain.getTotalDifficulty();
        if (td.compareTo(lowerUsefulDifficulty) > 0) {
            lowerUsefulDifficulty = td;
        }
    }

    private void heartBeat() {
        for (Channel peer : channelManager.getActivePeers()) {
            if (!peer.isIdle() && peer.getSyncStats().secondsSinceLastUpdate() > config.peerChannelReadTimeout()) {
                logger.info("Peer {}: no response after %d seconds", peer.getPeerIdShort(), config.peerChannelReadTimeout());
                peer.dropConnection();
            }
        }
    }
}
