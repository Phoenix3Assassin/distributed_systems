package app_kvECS;

import com.google.gson.Gson;
import common.helper.*;
import common.messages.Metadata;
import common.messages.zk_server.ZkServerCommunication;
import common.messages.zk_server.ZkToServerRequest;
import common.messages.zk_server.ZkToServerResponse;
import ecs.ECSNode;
import ecs.IECSNode;
import ecs.ZkStructureNodes;
import logger.LogSetup;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.zookeeper.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static common.helper.Script.createBashScript;
import static common.helper.Script.runScript;

public class ECSClient implements IECSClient {

    private static Logger logger = LogManager.getLogger(ECSClient.class);
    private static Logger loggerCnxn = LogManager.getLogger(ClientCnxn.class);

    private static final String PROMPT = "ECS_Client> ";
    private boolean stopClient = false;

    private String zkAddress;
    private int zkPort;

    private ZooKeeper zooKeeper;
    private ZkConnector zkConnector;
    private ZkNodeTransaction zkNodeTransaction;

    //zookeeper communication timeout
    private int reqResId = 0;
    private static final int TIME_OUT = 20000;

    private List<ECSNode> ecsNodes = new ArrayList<>();
    private Metadata metadata;

    // lock to prevent permanent node watch conflict with add, delete and start/stop
    public static volatile ReentrantLock lock = new ReentrantLock();
    private Thread heartBeatThread = null;


    public ECSClient(String zkHostname, int zkPort) throws IOException, EcsException {
        try {
            new LogSetup("logs/ecs/ecs_client.log", Level.ALL);
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.zkAddress = zkHostname;
        this.zkPort = zkPort;

        File file = new File("src/app_kvECS/" + "ecs.config");
        configureAvailableNodes(file);

    }

    // for performance testing purposes
    public ECSClient(String zkHostname, int zkPort, String configFile) throws IOException, EcsException {
        try {
            new LogSetup("logs/ecs/ecs_client.log", Level.ALL);
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.zkAddress = zkHostname;
        this.zkPort = zkPort;

        File file = new File("src/tests/testConfigs/" + "ecs.config");
        configureAvailableNodes(file);

    }

    private void configureAvailableNodes(File configFile) throws EcsException, IOException {
        if (!configFile.exists()) {
            throw new EcsException("Config file does not exist!");
        }

        final String DELIMITER = " ";
        final String DELIMITER_PATTERN = Pattern.quote(DELIMITER);

        ArrayList<String> fileLines = (ArrayList<String>) Files.readAllLines(configFile.toPath());
        for (String line : fileLines) {
            String[] tokenizedLine = line.split(DELIMITER_PATTERN);
            ecsNodes.add(new ECSNode(tokenizedLine[0], tokenizedLine[1], Integer.parseInt(tokenizedLine[2]), null,
                    false));
        }
        System.out.println(ecsNodes);

    }

    public void startZK() throws InterruptedException, IOException, KeeperException {
        // starting zookeeper on local machine on the default port and waiting for script to finish
        String zkStartScript = System.getProperty("user.dir") + "/src/app_kvECS/startZK.sh";
        Process startZkProcess = runScript(zkStartScript, logger);
        startZkProcess.waitFor();

        // connecting to zookeeper
        zkConnector = new ZkConnector();
        zooKeeper = zkConnector.connect(zkAddress + ":" + zkPort);

        // setting up
        setupZk();

    }

    private void setupZk() throws KeeperException, InterruptedException {
        // making sure zookeeper is clean (haven't run before)
        zkNodeTransaction = new ZkNodeTransaction(zooKeeper);
        zkNodeTransaction.delete(ZkStructureNodes.ROOT.getValue());

        // setting up structural nodes
        zkNodeTransaction.createZNode(ZkStructureNodes.NONE_HEART_BEAT.getValue(), null, CreateMode.PERSISTENT);
        zkNodeTransaction.createZNode(ZkStructureNodes.HEART_BEAT.getValue(), null, CreateMode.PERSISTENT);
        zkNodeTransaction.createZNode(ZkStructureNodes.METADATA.getValue(), null, CreateMode.PERSISTENT);
        zkNodeTransaction.createZNode(ZkStructureNodes.ZK_SERVER_REQUEST.getValue(), null, CreateMode.PERSISTENT);
        zkNodeTransaction.createZNode(ZkStructureNodes.ZK_SERVER_RESPONSE.getValue(), null, CreateMode.PERSISTENT);
        zkNodeTransaction.createZNode(ZkStructureNodes.SERVER_SERVER_REQUEST.getValue(), null, CreateMode.PERSISTENT);
        zkNodeTransaction.createZNode(ZkStructureNodes.SERVER_SERVER_RESPONSE.getValue(), null, CreateMode.PERSISTENT);

        try {
            zkNodeTransaction.createZNode(ZkStructureNodes.BACKUP_DATA.getValue(),null, CreateMode.PERSISTENT);
        }catch (KeeperException e){
            logger.info("backup_data node already exists in zookeeper :)");
        }


        // writing an empty metadata
        metadata = new Metadata(new ArrayList<>());
        zkNodeTransaction.write(ZkStructureNodes.METADATA.getValue(),
                new Gson().toJson(metadata, Metadata.class).getBytes());

        // setting a perm watch on node heart beat
        addPermanentHBNodeWatch(zooKeeper);
    }

    public void stopZK() throws InterruptedException {
        zkConnector.close();
        String zkStopScript = System.getProperty("user.dir") + "/src/app_kvECS/stopZK.sh";
        runScript(zkStopScript, logger);
    }


    @Override
    public boolean start() throws KeeperException, InterruptedException, EcsException {
        int noActiveServers = getNodesWithStatus(true).size();

        int reqId = reqResId++;
        ZkToServerRequest request = new ZkToServerRequest(reqId, ZkServerCommunication.Request.START, null, null);
        List<ZkToServerResponse> responses = processReqResp(noActiveServers, request);

        if (noActiveServers == responses.size()) {
            for (ZkToServerResponse response : responses) {
                if (!response.getZkSvrResponse().equals(ZkServerCommunication.Response.START_SUCCESS)) {
                    throw new EcsException("An unexpected response to start command!!");
                }
            }
            return true;
        } else {
            StringBuilder failedServers = new StringBuilder();

            ArrayList<IECSNode> runningNodes = getNodesWithStatus(true);

            boolean serverResponded;

            for (IECSNode iecsNode : runningNodes) {
                serverResponded = false;
                for (ZkToServerResponse response : responses) {
                    if (iecsNode.getNodeName().equals(response.getServerName())) {
                        serverResponded = true;
                        break;
                    }
                }

                if (!serverResponded) {
                    failedServers.append(iecsNode.getNodeName()).append(" ");
                }

            }


            logger.error(failedServers + "did not start or timed out!");
            return false;
        }

    }

    @Override
    public boolean stop() throws KeeperException, InterruptedException, EcsException {
        int noActiveServers = getNodesWithStatus(true).size();

        int reqId = reqResId++;
        ZkToServerRequest request = new ZkToServerRequest(reqId, ZkServerCommunication.Request.STOP, null, null);
        List<ZkToServerResponse> responses = processReqResp(noActiveServers, request);

        if (noActiveServers == responses.size()) {
            for (ZkToServerResponse response : responses) {
                if (!response.getZkSvrResponse().equals(ZkServerCommunication.Response.STOP_SUCCESS)) {
                    throw new EcsException("An unexpected response to stop command!!");
                }
            }
            return true;
        } else {
            StringBuilder failedServers = new StringBuilder();

            ArrayList<IECSNode> runningNodes = getNodesWithStatus(true);

            boolean serverResponded;

            for (IECSNode iecsNode : runningNodes) {
                serverResponded = false;
                for (ZkToServerResponse response : responses) {
                    if (iecsNode.getNodeName().equals(response.getServerName())) {
                        serverResponded = true;
                        break;
                    }
                }

                if (!serverResponded) {
                    failedServers.append(iecsNode.getNodeName()).append(" ");
                }

            }

            logger.error(failedServers + "did not stop or timed out!");
            return false;
        }


    }

    @Override
    public boolean shutdown() throws KeeperException, InterruptedException, EcsException {
        int noActiveServers = getNodesWithStatus(true).size();

        int reqId = reqResId++;
        ZkToServerRequest request = new ZkToServerRequest(reqId, ZkServerCommunication.Request.SHUTDOWN, null,
                null);
        List<ZkToServerResponse> responses = processReqResp(noActiveServers, request);

        for (ZkToServerResponse response : responses) {
            if (!response.getZkSvrResponse().equals(ZkServerCommunication.Response.SHUTDOWN_SUCCESS)) {
                throw new EcsException("An unexpected response to shutdown command!!");
            }

            for (ECSNode ecsNode : ecsNodes) {
                if (ecsNode.getNodeName().equals(response.getServerName())) {
                    ecsNode.setReserved(false);
                    ecsNode.setNodeHashRange(new String[2]);
                }
            }


        }

        // updating metadata
        ConsistentHash consistentHash = new ConsistentHash(ecsNodes);
        consistentHash.hash();

        metadata = new Metadata(ecsNodes);
        try {
            zkNodeTransaction.write(ZkStructureNodes.METADATA.getValue(), new Gson().toJson(metadata, Metadata
                    .class)
                    .getBytes());
        } catch (KeeperException | InterruptedException e) {
            logger.error("Metadata was not updated when shutting down! " + e.getMessage());
            return false;
        }


        if (noActiveServers == responses.size()) {
            return true;
        } else {
            logger.error("Shutdown was not successful or was partially successful!");
            return false;
        }


    }

    private List<ZkToServerResponse> processReqResp(int noOfResponses, ZkToServerRequest request) throws
            KeeperException,
            InterruptedException {
        // Sending request via zookeeper
        int reqId = request.getId();
        zkNodeTransaction.createZNode(ZkStructureNodes.ZK_SERVER_REQUEST.getValue() + ZkStructureNodes.REQUEST
                .getValue(), new Gson().toJson(request, ZkToServerRequest.class).getBytes(), CreateMode
                .PERSISTENT_SEQUENTIAL);

        List<ZkToServerResponse> responses = new ArrayList<>();

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < TIME_OUT && responses.size() != noOfResponses) {
            // receiving response
            findResponseId(reqId, responses);
        }

        return responses;
    }

    private void findResponseId(int reqId, List<ZkToServerResponse> responses) throws
            KeeperException, InterruptedException {

        ZkToServerResponse response;
        List<String> respNodePaths = zooKeeper.getChildren(ZkStructureNodes.ZK_SERVER_RESPONSE.getValue(), false);
        for (String nodePath : respNodePaths) {
            response = new Gson().fromJson(
                    new String(zkNodeTransaction.read(ZkStructureNodes.ZK_SERVER_RESPONSE.getValue() + "/" + nodePath)),
                    ZkToServerResponse.class);
            if (response != null && response.getId() == reqId) {
                responses.add(response);
                zkNodeTransaction.delete(ZkStructureNodes.ZK_SERVER_RESPONSE.getValue() + "/" + nodePath);
            }
        }
    }

    @Override
    public IECSNode addNode(String cacheStrategy, int cacheSize) {
        ArrayList<IECSNode> list = (ArrayList<IECSNode>) addNodes(1, cacheStrategy, cacheSize);

        if (list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public Collection<IECSNode> addNodes(int count, String cacheStrategy, int cacheSize) {
        if (count == 0) {
            return new ArrayList<>();
        }

        ArrayList<IECSNode> newEcsNodes = (ArrayList<IECSNode>) setupNodes(count, cacheStrategy, cacheSize);

        // Launch the server processes
        createRunSshScript(newEcsNodes, cacheStrategy, cacheSize);

        // call await nodes to wait for processes to start
        boolean success = awaitNodes(count, TIME_OUT);

        if (success) {
            // write node to added by setting boolean in ecs node
            for (IECSNode newEcsNode : newEcsNodes) {
                ecsNodes.get(ecsNodes.indexOf(newEcsNode)).setReserved(true);
                ((ECSNode) newEcsNode).setReserved(true);
            }
            logger.info("All nodes were added");
        } else {
            List<String> failedServers = new ArrayList<>();
            try {
                List<String> activeServers = zooKeeper.getChildren(ZkStructureNodes.NONE_HEART_BEAT.getValue(), false);

                Iterator<IECSNode> newEcsNodeIterator = newEcsNodes.iterator();
                while (newEcsNodeIterator.hasNext()) {
                    IECSNode newEcsNode = newEcsNodeIterator.next();
                    if (activeServers.contains(newEcsNode.getNodeName())) {
                        ecsNodes.get(ecsNodes.indexOf(newEcsNode)).setReserved(true);
                        ((ECSNode) newEcsNode).setReserved(true);
                    } else {
                        failedServers.add(newEcsNode.getNodeName());
                        newEcsNodeIterator.remove();
                    }
                }

                if (failedServers.size() > 0) {
                    logger.error("Timeout reached. Servers: " + failedServers + " might not has/have added");
                }

            } catch (KeeperException | InterruptedException e) {
                logger.error(e.getMessage());
            }
        }

        // did not start with metadata to account for failures so doing them at the end
        ConsistentHash consistentHash = new ConsistentHash(ecsNodes);
        consistentHash.hash();

        // updating data
        for (IECSNode newEcsNode : newEcsNodes) {
            for (ECSNode ecsNode : ecsNodes) {
                if (newEcsNode.getNodeName().equals(ecsNode.getNodeName())) {
                    ((ECSNode) newEcsNode).setNodeHashRange(ecsNode.getNodeHashRange());
                }
            }
        }

        metadata = new Metadata(ecsNodes);
        try {
            zkNodeTransaction.write(ZkStructureNodes.METADATA.getValue(), new Gson().toJson(metadata, Metadata.class)
                    .getBytes());
        } catch (KeeperException | InterruptedException e) {
            logger.error("Metadata was not updated! " + e.getMessage());
        }

        return newEcsNodes;
    }

    private void createRunSshScript(ArrayList<IECSNode> iEcsNodes, String cacheStrategy, int cacheSize) {

        ECSNode ecsNode;
        StringBuilder scriptContent = new StringBuilder();

        for (IECSNode iEcsNode : iEcsNodes) {
            ecsNode = (ECSNode) iEcsNode;
            scriptContent.append("ssh -n ").append(ecsNode.getNodeHost()).append(" ").append("nohup java -jar ")
                    .append("~/IdeaProjects/distributed_systems/m2-server.jar ").append(ecsNode.getNodeName()).append
                    (" ")
                    .append
                            (zkAddress).append(" ").append(zkPort).append(" ").append(ecsNode.getNodePort()).append("" +
                    " ").append
                    (cacheSize).append(" ").append(cacheStrategy).append(" ").append("&\n");
        }

        String scriptPath = System.getProperty("user.dir") + "/src/app_kvECS/ssh.sh";

        createBashScript(scriptPath, scriptContent.toString(), logger);
        Script.runScript(scriptPath, logger);
    }

    @Override
    public Collection<IECSNode> setupNodes(int count, String cacheStrategy, int cacheSize) {
        List<IECSNode> nodesToSetup = getNodesWithStatus(false);
        int size = nodesToSetup.size();
        if (count > size) {
            logger.error("Trying to setup " + count + " nodes but only " + size + " available!");
            return null;
        }

        // shuffling and removing random objects to only add required nodes
        Collections.shuffle(nodesToSetup);
        for (int i = 0; i < size - count; i++) {
            nodesToSetup.remove(0);
        }

        return nodesToSetup;
    }

    private ArrayList<IECSNode> getNodesWithStatus(boolean reserved) {
        List<IECSNode> availableNodes = new ArrayList<>(ecsNodes);
        CollectionUtils.filter(availableNodes, ecsNode -> ((ECSNode) ecsNode).isReserved() == reserved);
        return (ArrayList<IECSNode>) availableNodes;
    }


    @Override
    public boolean awaitNodes(int count, int timeout) {
        List<ECSNode> runningNodes = new ArrayList<>(ecsNodes);
        CollectionUtils.filter(runningNodes, ECSNode::isReserved);
        int preexistingHrNodes = runningNodes.size();

        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime <= timeout) {
            try {
                int numberHrChildrenNodes = zooKeeper.getChildren(ZkStructureNodes.NONE_HEART_BEAT.getValue(), false)
                        .size();
                if (numberHrChildrenNodes - preexistingHrNodes == count) {
                    return true;
                }
            } catch (KeeperException | InterruptedException e) {
                logger.error(e.getMessage());
            }
        }

        return false;
    }

    @Override
    public boolean removeNodes(Collection<String> nodeNames) {
        boolean found;
        boolean success = true;

        // checking that node you are trying to delete exist and server is active
        for (String nodeName : nodeNames) {
            found = false;
            for (ECSNode ecsNode : ecsNodes) {
                if (nodeName.equals(ecsNode.getNodeName()) && ecsNode.isReserved()) {
                    found = true;
                }
            }
            if (!found) {
                logger.error("Trying to remove node that are not active or do not exist");
                return false;
            }
        }

        // requesting delete nodes
        int reqId = reqResId++;
        ZkToServerRequest request = new ZkToServerRequest(reqId, ZkServerCommunication.Request.REMOVE_NODES, (List
                <String>) nodeNames, null);
        List<ZkToServerResponse> responses;
        try {
            responses = processReqResp(nodeNames.size(), request);
        } catch (KeeperException | InterruptedException e) {
            logger.error(e.getMessage());
            return false;
        }

        // processing response - removing node for nodes not to be deleted if it failed or did not respond
        if (nodeNames.size() == responses.size()) {
            for (ZkToServerResponse response : responses) {
                if (response.getZkSvrResponse().equals(ZkServerCommunication.Response.REMOVE_NODES_FAIL)) {
                    logger.error(response.getServerName() + " responded with a fail  Node");
                    success = false;
                    nodeNames.remove(response.getServerName());
                }
            }
        } else {
            for (ZkToServerResponse response : responses) {
                if (response.getZkSvrResponse().equals(ZkServerCommunication.Response.REMOVE_NODES_FAIL)) {
                    logger.error(response.getServerName() + " responded with a fail  Node");
                    nodeNames.remove(response.getServerName());
                }
            }
            // for the ones that did not respond
            boolean nodeResponded;
            Iterator<String> iterator = nodeNames.iterator();
            while (iterator.hasNext()) {
                String nodeToDelete = iterator.next();
                nodeResponded = false;
                for (ZkToServerResponse response : responses) {
                    if (nodeToDelete.equals(response.getServerName())) {
                        nodeResponded = true;
                    }
                }
                if (!nodeResponded) {
                    logger.error(nodeToDelete + " did not respond for delete request! Not deleting!");
                    iterator.remove();
                }
            }
            success = false;
        }

        for (ECSNode ecsNode : ecsNodes) {
            if (nodeNames.contains(ecsNode.getNodeName())) {
                ecsNode.setNodeHashRange(new String[2]);
                ecsNode.setReserved(false);
            }
        }


        ConsistentHash consistentHash = new ConsistentHash(ecsNodes);
        consistentHash.hash();

        metadata = new Metadata(ecsNodes);
        try {
            zkNodeTransaction.write(ZkStructureNodes.METADATA.getValue(), new Gson().toJson(metadata, Metadata
                    .class)
                    .getBytes());
        } catch (KeeperException | InterruptedException e) {
            logger.error("Metadata was not updated when removing nodes! " + e.getMessage());
            return false;
        }


        return success;
    }

    @Override
    public Map<String, IECSNode> getNodes() {
        Map<String, IECSNode> map = new HashMap<>();

        for (ECSNode ecsNode : ecsNodes) {
            if (ecsNode.isReserved()) {
                map.put(ecsNode.getNodeName(), ecsNode);
            }
        }

        return map;
    }

    @Override
    public IECSNode getNodeByKey(String key) {
        try {
            Metadata metadata = new Gson().fromJson(
                    new String(zkNodeTransaction.read(ZkStructureNodes.METADATA.getValue())),
                    Metadata.class);

            return metadata.getResponsibleServer(key);

        } catch (KeeperException | InterruptedException e) {
            logger.error(e.getMessage());
        }
        return null;
    }

    private synchronized void addPermanentHBNodeWatch(final ZooKeeper zooKeeper) throws KeeperException,
            InterruptedException {
        zooKeeper.getChildren(ZkStructureNodes.HEART_BEAT.getValue(), event -> {
            if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                try {
                    addPermanentHBNodeWatch(zooKeeper);
                    ECSClient.lock.lock();
                    checkHBStatus(zooKeeper);
                } catch (KeeperException | InterruptedException e) {
                    logger.error(e.getMessage());
                } finally {
                    ECSClient.lock.unlock();
                }
            }
        });
    }


    private synchronized void checkHBStatus(ZooKeeper zooKeeper) throws KeeperException,
            InterruptedException {
        List<String> nodesHB = zooKeeper.getChildren(ZkStructureNodes.HEART_BEAT.getValue(), false);
        List<String> nodesNHB = zooKeeper.getChildren(ZkStructureNodes.NONE_HEART_BEAT.getValue(), false);


        if (nodesNHB.size() - nodesHB.size() > 0) {
            //node deleted
            List<String> crashedNodes = ((List<String>) CollectionUtils.subtract(nodesNHB, nodesHB));
            logger.warn(crashedNodes.toString() + " crashed!!");
            for (String crashedNode : crashedNodes) {
                zkNodeTransaction.delete(ZkStructureNodes.NONE_HEART_BEAT.getValue() + "/" + crashedNode);

                logger.info("attempting to fetch backup for node " + crashedNode + "...");
                // getting the next and next next server of failed server so we can attempt backup recovery
                ECSNode nextServerNode = metadata.getNextServer(crashedNode, new ArrayList<>(Collections
                        .singletonList(crashedNode)));
                ECSNode nextNextServerNode = metadata.getNextServer(crashedNode, new ArrayList<>(Arrays
                        .asList(crashedNode, nextServerNode.getNodeName())));

                ZkToServerRequest request = null;
                int reqId = reqResId++;
                logger.info("Attempting recovery from first backup server...");
                if (nextServerNode != null && !crashedNodes.contains(nextServerNode.getNodeName())) {
                    logger.info("Found first backup server! Connecting...");
                    //creating request
                    request = new ZkToServerRequest(reqId, ZkServerCommunication.Request
                            .TRANSFER_BACKUP_DATA, new ArrayList<>(Collections.singletonList(nextServerNode
                            .getNodeName())), metadata.getRange(crashedNode));

                } else {
                    logger.info("Attempting recovery from second backup server...");
                    if (nextNextServerNode != null && !crashedNodes.contains(nextNextServerNode.getNodeName())) {
                        logger.info("Found second backup server! Connecting...");
                        // creating request
                        request = new ZkToServerRequest(reqId, ZkServerCommunication.Request
                                .TRANSFER_BACKUP_DATA, new ArrayList<>(Collections.singletonList(nextNextServerNode
                                .getNodeName())), metadata.getRange(crashedNode));
                    }
                }

                if (request == null) {
                    logger.fatal("Data on server " + crashedNode + " was lost!");
                } else {
                    logger.info("requesting with...");
                    logger.info(request.toString());
                    List<ZkToServerResponse> responses;
                    try {
                        responses = processReqResp(1, request);
                        if (responses.size() == 0 || responses.get(0).getZkSvrResponse().equals(ZkServerCommunication
                                .Response.TRANSFER_BACKUP_DATA_FAIL)) {
                            if(responses.size() !=0) {
                                logger.info(responses.get(0).toString());
                            }
                            logger.fatal("Data on server " + crashedNode + " was lost as server responded with backup" +
                                    " fail or timed out!");
                        }else {
                            logger.info("Data successfully recovered!");
                        }
                    } catch (KeeperException | InterruptedException e) {
                        logger.fatal("Data on server " + crashedNode + " was lost due to next error!");
                        logger.error(e.getMessage());
                    }
                }
            }

            // updating metadata
            for (ECSNode ecsNode : ecsNodes) {
                if (crashedNodes.contains(ecsNode.getNodeName())) {
                    ecsNode.setNodeHashRange(new String[2]);
                    ecsNode.setReserved(false);
                }
            }


            ConsistentHash consistentHash = new ConsistentHash(ecsNodes);
            consistentHash.hash();

            metadata = new Metadata(ecsNodes);
            try {
                zkNodeTransaction.write(ZkStructureNodes.METADATA.getValue(), new Gson().toJson(metadata, Metadata
                        .class)
                        .getBytes());
            } catch (KeeperException | InterruptedException e) {
                logger.fatal("Metadata was not updated when recovering crashed nodes! " + e.getMessage());
            }
        }
    }


    public void run() {
        while (!stopClient) {
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
            System.out.print(PROMPT);

            try {
                String cmdLine = stdin.readLine();
                lock.lock();
                this.handleCommand(cmdLine);
            } catch (IOException e) {
                stopClient = true;
                printError("CLI does not respond - Application terminated ");
            } catch (InterruptedException | KeeperException | EcsException e) {
                logger.error(e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    private void handleCommand(String cmdLine) throws EcsException, KeeperException, InterruptedException {
        String[] tokens = cmdLine.split("\\s+");
        Arrays.stream(tokens)
                .filter(s -> s != null && s.length() > 0
                ).collect(Collectors.toList()).toArray(tokens);

        if (tokens.length != 0 && tokens[0] != null) {
            switch (tokens[0]) {
                case "start": {
                    System.out.println(PROMPT + start());
                    break;
                }
                case "stop": {
                    System.out.println(PROMPT + stop());
                    break;
                }
                case "shutdown": {
                    System.out.println(PROMPT + shutdown());
                    break;
                }
                case "addNode": {
                    Object[] a = getArguments(tokens, new ArgType[]{ArgType.STRING, ArgType.INTEGER});
                    if (a == null)
                        return;
                    IECSNode node = addNode((String) a[0], (int) a[1]);
                    if (node == null) {
                        System.out.println(PROMPT + "No nodes added!");
                    } else {
                        System.out.println(PROMPT + node.getNodeName() + " started!");
                    }
                    break;
                }
                case "addNodes": {
                    Object[] a = getArguments(tokens, new ArgType[]{ArgType.INTEGER, ArgType.STRING, ArgType.INTEGER});
                    if (a == null)
                        return;
                    Collection<IECSNode> nodes = addNodes((int) a[0], (String) a[1], (int) a[2]);

                    if (nodes.isEmpty()) {
                        System.out.println(PROMPT + "No nodes added!");
                    } else {
                        for (IECSNode node : nodes) {
                            System.out.println(node);
                        }
                        System.out.println("was/were added!");
                    }
                    break;
                }
                case "removeNodes": {
                    if (tokens.length < 2) {
                        printHelp();
                        return;
                    }
                    ArrayList<String> temp = new ArrayList<String>(
                            Arrays.asList(Arrays.copyOfRange(tokens, 1, tokens.length)));
                    System.out.println(PROMPT + removeNodes(temp));
                    break;
                }
                case "logLevel": {
                    if (tokens.length == 2) {
                        String level = setLevel(tokens[1]);
                        if (StringUtils.isEmpty(level)) {
                            printError("No valid log level!");
                            printPossibleLogLevels();
                        } else {
                            System.out.println(PROMPT +
                                    "Log level changed to level " + level);
                        }
                    } else {
                        printError("Invalid number of parameters!");
                    }

                    break;
                }
                case "help": {
                    printHelp();
                    break;
                }
                case "quit":
                    stopClient = true;
                    if (heartBeatThread != null && heartBeatThread.isAlive()) {
                        heartBeatThread.stop();
                    }
                    break;
                default: {
                    printError("Unknown command");
                    printHelp();
                    break;
                }
            }
        }
    }

    private void printHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append(PROMPT).append("ECS CLIENT HELP (Usage):\r\n");
        sb.append(PROMPT);
        sb.append("::::::::::::::::::::::::::::::::");
        sb.append("::::::::::::::::::::::::::::::::\r\n");
        sb.append(PROMPT).append("start");
        sb.append("\n\t\t\t\t Starts the storage service by calling start() on all KVServer instances " +
                "that participate in the service.\r\n");
        sb.append(PROMPT).append("stop");
        sb.append("\n\t\t\t\t Stops the service; all participating KVServers are stopped for processing client " +
                "requests " +
                "but the processes remain running.\r\n");
        sb.append(PROMPT).append("addNode <Cache Size> <Replacement Strategy>");
        sb.append("\n\t\t\t\t Create a new KVServer with the specified cache size and replacement strategy and " +
                "add it to the storage service at an arbitrary position.\r\n");
        sb.append(PROMPT).append("addNodes <# Nodes> <Cache Size> <Replacement Strategy>");
        sb.append("\n\t\t\t\t Randomly choose <numberOfNodes> servers from the available machines and start the " +
                "KVServer " +
                "by issuing an SSH call to the respective machine. This call launches the storage server with the " +
                "specified cache size and replacement strategy. For simplicity, locate the KVServer.jar in the same " +
                "directory as the ECS. All storage servers are initialized with the metadata and any persisted " +
                "data, and remain in state stopped.\r\n");
        sb.append(PROMPT).append("setupNodes <Nodes to Wait For> <Cache Strategy> <Cache Size>");
        sb.append("\n\t\t\t\t Wait for all nodes to report status or until timeout expires.\r\n");
        sb.append(PROMPT).append("awaitNodes <Nodes to Wait For> <Timeout>");
        sb.append("\n\t\t\t\t Removes nodes with names matching the nodeNames array.\r\n");
        sb.append(PROMPT).append("removeNodes [array of nodes] e.g. <node1> <node2> <node3> ...");
        sb.append("\n\t\t\t\t Remove a server from the storage service at an arbitrary position. \r\n");
        sb.append(PROMPT).append("getNodes");
        sb.append("\n\t\t\t\t Get a map of all nodes.\r\n");
        sb.append(PROMPT).append("getNodeByKey <key>");
        sb.append("\n\t\t\t\t Get the specific node responsible for the given key.\r\n");
        sb.append(PROMPT).append("logLevel");
        sb.append("\n\t\t\t\t changes the logLevel: ");
        sb.append("ALL | DEBUG | INFO | WARN | ERROR | FATAL | OFF \r\n");

        sb.append(PROMPT).append("quit ");
        sb.append("\n\t\t\t\t exits the program");
        System.out.println(sb.toString());
    }

    private void printPossibleLogLevels() {
        System.out.println(PROMPT
                + "Possible log levels are:");
        System.out.println(PROMPT
                + "ALL | DEBUG | INFO | WARN | ERROR | FATAL | OFF");
    }

    private String setLevel(String levelString) {

        if (levelString.equals(Level.ALL.toString())) {
            logger.setLevel(Level.ALL);
            return Level.ALL.toString();
        } else if (levelString.equals(Level.DEBUG.toString())) {
            logger.setLevel(Level.DEBUG);
            return Level.DEBUG.toString();
        } else if (levelString.equals(Level.INFO.toString())) {
            logger.setLevel(Level.INFO);
            return Level.INFO.toString();
        } else if (levelString.equals(Level.WARN.toString())) {
            logger.setLevel(Level.WARN);
            return Level.WARN.toString();
        } else if (levelString.equals(Level.ERROR.toString())) {
            logger.setLevel(Level.ERROR);
            return Level.ERROR.toString();
        } else if (levelString.equals(Level.FATAL.toString())) {
            logger.setLevel(Level.FATAL);
            return Level.FATAL.toString();
        } else if (levelString.equals(Level.OFF.toString())) {
            logger.setLevel(Level.OFF);
            return Level.OFF.toString();
        }
        return null;
    }

    private void printError(String error) {
        System.out.println(PROMPT + "Error! " + error);
    }

    private Object[] getArguments(String[] arguments, ArgType[] types) {
        Object[] array = new Object[types.length];
        int i = 1;
        try {
            for (ArgType type : types) {
                switch (type) {
                    case INTEGER:
                        array[i - 1] = Integer.parseInt(arguments[i++]);
                        break;
                    case STRING:
                        array[i - 1] = arguments[i++];
                        break;
                }
            }
        } catch (Exception e) {
            printHelp();
            return null;
        }
        return array;
    }


    /**
     * @param args config file
     */
    public static void main(String[] args) throws EcsException, IOException, InterruptedException, KeeperException {
        if (args.length != 2) {
            throw new EcsException("Incorrect # of arguments for ECS Client!");
        }
        ECSClient app = new ECSClient(args[0], Integer.parseInt(args[1]));
        app.startZK();
        app.run();
        app.stopZK();
    }
}
