package com.elastic.support.diagnostics;

import com.elastic.support.InputParams;
import com.elastic.support.SystemProperties;
import com.elastic.support.SystemUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipOutputStream;

//@Service
//public class CollectionService implements ApplicationContextAware {
public class CollectionService {

    private static final Logger logger = LoggerFactory.getLogger(CollectionService.class);

    public void run(InputParams inputs) {

        logger.debug(inputs.toString());

        // Get the configFile
        Map configMap = retrieveConfiguration(inputs.getConfigFile());

        JsonNode rootNode = getManifestInput(inputs.getManifestFile());
        String clusterName = rootNode.path("clusterName").textValue();

        // Set up the output directory
        String outputDir = setOutputDir(inputs);
        System.out.println("Results will be written to: " + outputDir);

        String targetDir = outputDir + SystemProperties.fileSeparator + "diagnostics-artifacts-" + clusterName;

        try {
            SystemUtils.deleteDir(targetDir, true);
            Files.createDirectories(Paths.get(targetDir));
        } catch (Exception e) {
            logger.error("Output dir could not be created", e);
            throw new RuntimeException("Could not create output directory - see logs for details.");
        }

        String hostName = processNodes(rootNode, targetDir);

        processOsCmds(configMap, targetDir, inputs);

        zipResults(targetDir, hostName);

    }

    public Set getIpAndHostData(){

        // Check system for NIC's to get ip's and hostnames
        HashSet ipAndHosts = new HashSet();

        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();

            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                ipAndHosts.add(nic.getDisplayName());
                Enumeration<InetAddress> inets = nic.getInetAddresses();

                while (inets.hasMoreElements()) {
                    InetAddress inet = inets.nextElement();
                    ipAndHosts.add(inet.getHostAddress());
                    ipAndHosts.add(inet.getHostAddress());
                }
            }
        }
        catch (Exception e) {
            logger.error("Error occurred acquiring IP's and hostnames", e);
        }

        logger.debug("IP and Hostname list:" + ipAndHosts);
        return ipAndHosts;
    }


    public JsonNode getManifestInput(String dir) {

        byte[] output;
        JsonNode root = null;
        try {
            output = Files.readAllBytes(Paths.get(dir));
            String nodeString = new String(output);
            ObjectMapper mapper = new ObjectMapper();
            root = mapper.readTree(nodeString);
        } catch (IOException e) {
            logger.error("Could not read manifest file from " + dir, e);
            throw new RuntimeException("Error reading manifest file, please check the path.");
        }

        return root;
    }

    public String processNodes(JsonNode root, String target) {

        String hostName = null;

        try {
            Set ipAndHosts = this.getIpAndHostData();

            JsonNode nodes = root.path("nodes");
            String clusterName = root.path("clusterName").textValue();

            Iterator<JsonNode> it = nodes.iterator();

            while (it.hasNext()) {
                JsonNode n = it.next();
                String host = n.path("host").asText();
                String ip = n.path("ip").asText();

                // if the host we're on doesn't match up with the node entry
                // then bypass it and move to the next node
                if (! (ipAndHosts.contains(ip) || ipAndHosts.contains(host) ) ) {
                    continue;
                }

                //If we're going to process one, use the first hostname encountered for the file name.
                if (hostName == null){
                    hostName = host;
                }

                String name = n.path("name").asText();
                String config = n.path("config").asText();
                String conf = n.path("conf").asText();
                String logs = n.path("logs").asText();
                String home = n.path("home").asText();

                // Create a directory for this node
                String nodeDir = target + SystemProperties.fileSeparator + name;
                Files.createDirectories(Paths.get(nodeDir));

                String configFileLoc = determineConfigLocation(conf, config, home);

                // Copy the config file
                SystemUtils.copyFile(configFileLoc, nodeDir + SystemProperties.fileSeparator + "elasticsearch.yml");

                if ("".equals(logs)) {
                    logs = home + SystemProperties.fileSeparator + "logs";
                }

                // Copy the main and slow logs
                SystemUtils.copyFile(logs + SystemProperties.fileSeparator + clusterName + ".log", nodeDir + SystemProperties.fileSeparator + clusterName + ".log");
                SystemUtils.copyFile(logs + SystemProperties.fileSeparator + clusterName + "_index_indexing_slowlog.log", nodeDir + SystemProperties.fileSeparator + clusterName + "_index_indexing_slowlog.log");
                SystemUtils.copyFile(logs + SystemProperties.fileSeparator + clusterName + "_index_search_slowlog.log", nodeDir + SystemProperties.fileSeparator + clusterName + "_index_search_slowlog.log");

                logger.debug("processed node:\n" + name);
            }
        } catch (Exception e) {
            logger.error("Error processing the nodes manifest:\n", e);
            throw new RuntimeException("Error processing node");
        }

        return hostName;
    }

    public String determineConfigLocation(String conf, String config, String home){

        String configFileLoc;

        //Check for the config location
        if (!"".equals(config)) {
            configFileLoc = config;
        } else if (!"".equals(conf)) {
            configFileLoc = conf + "elasticsearch.yml";
        } else {
            configFileLoc = home + SystemProperties.fileSeparator + "config" + SystemProperties.fileSeparator + "elasticsearch.yml";
        }

        return configFileLoc;
    }

    public String checkOS() {
        String osName = SystemProperties.osName.toLowerCase();
        if (osName.contains("windows")) {
            return "winOS";
        } else if (osName.contains("linux")) {
            return "linuxOS";
        } else if (osName.contains("darwin") || osName.contains("mac os x")) {
            return "macOS";
        }
        else {
            logger.error("Failed to detect operating system!");
            throw new RuntimeException("Unsupported OS");
        }
    }

    public void processOsCmds(Map configMap, String targetDir, InputParams inputs) {
        String os = checkOS();
        Map<String, String> osCmds = (Map<String, String>) configMap.get(os);

        ProcessBuilder pb = new ProcessBuilder();
        pb.redirectErrorStream(true);

        Iterator<Map.Entry<String, String>> iter = osCmds.entrySet().iterator();
        List cmds = new ArrayList();

        try {
            while (iter.hasNext()) {
                Map.Entry<String, String> entry = (Map.Entry<String, String>) iter.next();
                String cmdLabel = entry.getKey();
                String cmdText = entry.getValue();
                StringTokenizer st = new StringTokenizer(cmdText, " ");
                while (st.hasMoreTokens()) {
                    cmds.add(st.nextToken());
                }

                pb.redirectOutput(new File(targetDir + SystemProperties.fileSeparator + cmdLabel + ".txt"));
                pb.command(cmds);
                Process pr = pb.start();
                pr.waitFor();
                cmds.clear();

            }
        } catch (Exception e) {
            logger.error("Error processing system commands", e);
        }
    }

    public String setOutputDir(InputParams inputs) {

        if ("cwd".equalsIgnoreCase(inputs.getOutputDir())) {
            return SystemProperties.userDir;
        } else {
            return inputs.getOutputDir();
        }
    }

    public Map retrieveConfiguration(String config) {

        InputStream is;
        try {
            if (config == null) {
                is = this.getClass().getClassLoader().getResourceAsStream("cmds.yml");
            } else {
                is = new FileInputStream(new File(config));
            }

            return SystemUtils.readUYaml(is, true);

        } catch (Exception e) {
            logger.error("Error retriving configuration", e);
            throw new RuntimeException("Could not retrieve configuration - was a valid absolute path specified?");
        }
    }

    public void zipResults(String dir, String hostName) {

        try {
            File file = new File(dir);
            String zipFileName = dir + "-" + hostName + ".zip";
            ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFileName));
            out.setLevel(ZipOutputStream.DEFLATED);
            SystemUtils.zipDir("", file, out);
            logger.debug("Archive " + zipFileName);
            file.delete();
            logger.debug("Temp directory " + dir + " was deleted.");
            out.close();

        } catch (Exception ioe) {
            logger.error("Couldn't create archive.\n", ioe);
            throw new RuntimeException(("Error creating compressed archive from statistics files." ));
        }
    }
}


