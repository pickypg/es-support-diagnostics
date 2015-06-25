package com.elastic.support.diagnostics;

import com.elastic.support.InputParams;
import com.elastic.support.SystemProperties;
import com.elastic.support.SystemUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipOutputStream;

@Service
public class DiagnosticService {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticService.class);
    private static final String PRIOR_VERSION = "1.0.0";


    private RestTemplate restTemplate;

    public void run(InputParams inputs) {

        logger.debug(inputs.toString());

        // Get the yaml config file, either default or passed in
        Map configMap = retrieveConfiguration(inputs.getConfigFile());

        // Create an SSL enabled version - it will work for regular HTTP as well.
        // Note that it will function like a browser where you tell it to go ahead and trust an unknown CA
        int connectTimeout = (Integer)configMap.get("connectTimeout");
        int requestTimeout =  (Integer)configMap.get("requestTimeout");

        DiagnosticRequestFactory diagnosticRequestFactory = new DiagnosticRequestFactory(connectTimeout, requestTimeout);
        restTemplate = new RestTemplate(diagnosticRequestFactory.getSslReqFactory());
        HttpEntity<String> request = configureAuth(inputs);

        // Get the version number and cluster name fromt the JSON returned
        // by just submitting the host/port combo
        Map resultMap = this.getVersionData(inputs.getUrl(), request);
        String clusterName = (String) resultMap.get("cluster_name");
        Map versionMap = (Map) resultMap.get("version");
        String version = (String) versionMap.get("number");

        // use the appropriate combination of statements - overlay the current with the prior ones
        // if using an older version
        Map<String, String> statements = getConfiguredStatements(version, configMap);

        // Set up where we want to put the results - it may come in from the command line
        String outputDir = setOutputDir(inputs);
        System.out.println("Results will be written to: " + outputDir);
        String tempDir = outputDir + SystemProperties.fileSeparator + clusterName + "-diagnostics";

        // Create the temp directory - delete if first if it exists from a previous run
        try {
            SystemUtils.deleteDir(tempDir);
            Files.createDirectories(Paths.get(tempDir));
        } catch (IOException e) {
            logger.error("Temp dir could not be created", e);
            throw new RuntimeException("Could not create temp directory - see logs for details.");
        }

        logger.debug("Created temp directory: " + tempDir);
        System.out.println("Creating " + tempDir + " as temporary directory.\n");

        // They can either run a full diagnostic or just create
        // a manifest file to collect logs and configs.  Note that the
        // manifest gets created either way.
        if (inputs.isGenManifest()) {
            logger.debug("Generating manifest file only.");
            String query = statements.get("nodes");
            runManifestQuery(query, inputs.getUrl(), tempDir, request);
        }

        Set<Map.Entry<String, String>> entries = statements.entrySet();

        for(Map.Entry<String, String> entry: entries){
            logger.debug("Generating full diagnostic.");
            String queryName = entry.getKey();
            String query = entry.getValue();
            logger.debug(": now processing " + queryName + ", " + query);
            runDiagnosticQuery(configMap, inputs.getUrl(), queryName, query, tempDir, request);
        }

        logger.debug("Finished retrieving queries.");

        zipResults(tempDir);

        System.out.println("Finished archiving results and deleting temp directories");

    }

    public Map<String, String> getConfiguredStatements(String version, Map configMap) {

        // use the appropriate combination of statements - overlay the current with the prior ones
        // if using an older version
        Map<String, String> statements = (Map<String, String>) configMap.get("currentQueries");

        if (PRIOR_VERSION.equals(version)) {
            String recovery = (String) configMap.get("recovery-1.0.0");
            statements.put("recovery", recovery);
        }

        return statements;

    }

    public Map getVersionData(String url, HttpEntity<String> request) {

        Map versionMap;

        try {
            String result = submitRequest(url, "", request);
            ObjectMapper mapper = new ObjectMapper();
            versionMap = mapper.readValue(result, LinkedHashMap.class);
        } catch (Exception e) {
            logger.error("Error getting version.", e);
            throw new RuntimeException("Error retrieving version data. " + e.getMessage());
        }

        return versionMap;
    }

    public void runDiagnosticQuery(Map configMap, String url, String key, String query, String target, HttpEntity<String> request) {

        List textFileExtensions = (List) configMap.get("textFileExtensions");
        String result;

        try {
            result = submitRequest(url, query, request);
            String ext;
            if (textFileExtensions.contains(key)) {
                ext = ".txt";
            } else {
                ext = ".json";
            }
            String filename = target + SystemProperties.fileSeparator + key + ext;

            Files.write(Paths.get(filename), result.getBytes());

            logger.debug("Done writing:" + filename);

            System.out.println("Statistic " + key + " was retrieved and saved to disk.");

            //If it's nodes then we add to the the collection file output
            if (key.equalsIgnoreCase("nodes")) {
                writeClusterManifest(result, target);
            }

        } catch (IOException ioe) {
            // If something goes wrong write the detail stuff to the log and then rethrow a RuntimeException
            // that will be caught at the top level and will contain a more generic user message
            logger.error("Diagnostic for:" + key + "couldn't be written", ioe);
            throw new RuntimeException("Error writing file for statistic:" + key + ". There may be issues with the file system.  You may need to check for permissions or space issues.");
        } catch (Exception e) {
            // If they aren't Shield users this will generate an Exception so if it fails just continue and don't rethrow an Exception
            if (!"licenses".equalsIgnoreCase(key)) {
                logger.error("Error retrieving the following diagnostic:  " + key + " - this stat will not be included.", e);
            }
        }
    }

    public void runManifestQuery(String query, String url, String target, HttpEntity<String> request) {

        try {
            String result;
            result = submitRequest(url, query, request);
            writeClusterManifest(result, target);
        } catch (Exception e) {
            // If something goes wrong write the detail stuff to the log and then rethrow a RuntimeException
            // that will be caught at the top level and will contain a more generic user message
            logger.error("Error retrieving or writing manifest", e);
            throw new RuntimeException("Error writing manifest file.");
        }

    }

    public String submitRequest(String url, String query, HttpEntity<String> request) {

        String result;
        try {
            String submission = url + "/" + query;
            logger.debug("Submitting: " + url);
            ResponseEntity<String> response = restTemplate.exchange(submission, HttpMethod.GET, request, String.class);
            result = response.getBody();
            logger.debug(result);

        } catch (RestClientException e) {
            String msg = "Please check log file for additional details.";
            logger.error("Error submitting request\n:", e);
            if (e.getMessage().contains("401 Unauthorized")) {
                msg = "Authentication failure: invalid login credentials.\n" + msg;
            }
            throw new RuntimeException(msg);
        }

        return result;
    }

    private void writeClusterManifest(String nodeString, String target) {

        try {
            //Move up one from the temp directory that will be zipped and subsequently deleted
            int idx = target.lastIndexOf(SystemProperties.fileSeparator) + 1;
            String dir = target.substring(0, idx);


            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(nodeString);
            JsonNode nodes = root.path("nodes");
            String clusterName = root.path("cluster_name").textValue();

            Iterator<JsonNode> it = nodes.iterator();

            Map<String, Object> cluster = new HashMap<>();
            cluster.put("clusterName", clusterName);
            cluster.put("collectionDate", SystemUtils.getUtcDateString());
            List nodeList = new ArrayList();
            cluster.put("nodes", nodeList);

            while (it.hasNext()) {
                JsonNode n = it.next();
                String host = n.path("host").asText();
                String ip = n.path("ip").asText();
                String name = n.path("name").asText();

                JsonNode settings = n.path("settings");
                String configFile = settings.path("config").asText();

                JsonNode nodePaths = settings.path("path");
                String logs = nodePaths.path("logs").asText();
                String conf = nodePaths.path("conf").asText();
                String home = nodePaths.path("home").asText();

                Map<String, String> tmp = new HashMap<>();
                tmp.put("host", host);
                tmp.put("ip", ip);
                tmp.put("name", name);
                tmp.put("config", configFile);
                tmp.put("conf", conf);
                tmp.put("logs", logs);
                tmp.put("home", home);
                nodeList.add(tmp);

                logger.debug("processed node:\n" + tmp);
            }

            File manifest = new File(target + SystemProperties.fileSeparator + clusterName + "-manifest.json");
            mapper.writeValue(manifest, cluster);

            manifest = new File(dir + clusterName + "-manifest.json");
            if (manifest.exists()) {
                manifest.delete();
            }
            mapper.writeValue(manifest, cluster);

        } catch (Exception e) {
            logger.error("Error parsing or writing the collector file:\n", e);
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
                is = this.getClass().getClassLoader().getResourceAsStream("stats.yml");
            } else {
                is = new FileInputStream(new File(config));
            }

            return SystemUtils.readUYaml(is, true);

        } catch (Exception e) {
            logger.error("Error retriving configuration", e);
            throw new RuntimeException("Could not retrieve configuration - was a valid absolute path specified?");
        }

    }

    public HttpEntity<String> configureAuth(InputParams inputs) {

        HttpHeaders headers = new HttpHeaders();

        // If we need authentication
        if (inputs.isSecured()) {
            String plainCreds = inputs.getUsername()
                    + ":" + inputs.getPassword();
            byte[] plainCredsBytes = plainCreds.getBytes();
            byte[] base64CredsBytes = Base64.encodeBase64(plainCredsBytes);
            String base64Creds = new String(base64CredsBytes);
            headers.add("Authorization", "Basic " + base64Creds);
        }

        return new HttpEntity<>(headers);

    }
    public void zipResults(String dir) {

        try {
            File file = new File(dir);
            ZipOutputStream out = new ZipOutputStream(new FileOutputStream(dir + ".zip"));
            out.setLevel(ZipOutputStream.DEFLATED);
            SystemUtils.zipDir("", file, out);
            logger.debug("Archive " + dir + ".zip was created");
            file.delete();
            logger.debug("Temp directory " + dir + " was deleted.");
            out.close();

        } catch (Exception ioe) {
            logger.error("Couldn't create archive.\n", ioe);
            throw new RuntimeException(("Error creating compressed archive from statistics files." ));
        }
    }
}


