/*
 * SPDX-FileCopyrightText: 2025 CESSDA ERIC (support@cessda.eu)
 * 
 * SPDX-License-Identifier: Apache-2.0
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package cessda.fairtests;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MetadataUsesCessdaPids
 *
 * Checks whether a CESSDA Data Catalogue record contains a Persistent
 * Identifier (PID)
 * belonging to an approved PID schema (fetched dynamically from the CESSDA
 * vocabulary).
 *
 * Behaviour:
 * - Examines JSON field {@code agency} for agency values.
 * - Returns "pass" if any agency matches an approved PID schema.
 * - Returns "fail" if none match or if no PIDs are present.
 * - Returns "indeterminate" if an error occurs (e.g. network or JSON parsing).
 */
public class MetadataUsesCessdaPids {

    private static final String DDI_NAMESPACE = "ddi:codebook:2_5";
    private static final String OAI_PMH_BASE = "https://datacatalogue.cessda.eu/oai-pmh/v0/oai?verb=GetRecord&metadataPrefix=oai_ddi25&identifier=";
    private static final String DDI_SEARCH_PATH = "//ddi:codeBook/ddi:stdyDscr/ddi:citation/ddi:titlStmt/ddi:IDNo";

    private static final String DETAIL_SEGMENT = "/detail/";
    private static final String RESULT_PASS = "pass";
    private static final String RESULT_FAIL = "fail";
    private static final String RESULT_INDETERMINATE = "indeterminate";
    private static final String PID_VOCAB_URL = "https://vocabularies.cessda.eu/v2/vocabularies/CessdaPersistentIdentifierTypes/1.0.0?languageVersion=en-1.0.0&format=json";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private static volatile Set<String> cachedApprovedSchemas;
    private final DocumentBuilderFactory documentBuilderFactory;
    private final XPathFactory xPathFactory;
    private static final Logger logger = Logger.getLogger(MetadataUsesCessdaPids.class.getName());

    public MetadataUsesCessdaPids() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
        this.documentBuilderFactory = DocumentBuilderFactory.newInstance();
        this.documentBuilderFactory.setNamespaceAware(true);
        this.xPathFactory = XPathFactory.newInstance();
        logger.setLevel(Level.INFO);
    }

    /**
     * Checks whether a CESSDA record contains an approved PID schema.
     *
     * @param url The CESSDA detail URL (e.g.
     *            https://datacatalogue.cessda.eu/detail/abc123?lang=en)
     * @return "pass", "fail", or "indeterminate"
     */
    public String containsApprovedPid(String url) {
        try {
            String recordId = extractRecordIdentifier(url);

            Document doc = fetchAndParseDocument(OAI_PMH_BASE + recordId);
            return checkDocumentForApprovedPid(doc, recordId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logSevere("Error processing document: " + e.getMessage());
        } catch (Exception e) {
            logSevere("Error: " + e.getMessage());
        }
        return RESULT_INDETERMINATE;
    }

    /**
     * Extract the record identifier from the CESSDA detail URL.
     * 
     * @param url - the CESSDA detail URL
     * @return the record identifier
     */
    private String extractRecordIdentifier(String url) {
        if (!url.contains(DETAIL_SEGMENT)) {
            throw new IllegalArgumentException("URL must contain '" + DETAIL_SEGMENT + "': " + url);
        }
        String cleanUrl = url.split("\\?")[0];
        String id = cleanUrl.substring(cleanUrl.indexOf(DETAIL_SEGMENT) + DETAIL_SEGMENT.length());
        if (id.isEmpty()) {
            throw new IllegalArgumentException("No record identifier in URL: " + url);
        }
        return id;
    }

    /**
     * Fetch the OAI-PMH GetRecord XML and parse to extract the DDI codeBook
     * element.
     * 
     * @param url - the OAI-PMH GetRecord URL
     * @return Document - the parsed DDI document
     * @throws IOException          - if an I/O error occurs
     * @throws InterruptedException - if the operation is interrupted
     */
    public Document fetchAndParseDocument(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/xml, text/xml, */*")
                .header("User-Agent", "Java-HttpClient")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200)
            throw new IOException("Failed to fetch document: HTTP " + response.statusCode());
        if (response.body() == null || response.body().length == 0)
            throw new IOException("Empty response body");

        try {
            logInfo("Parsing XML response from OAI-PMH endpoint at: " + url);
            DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
            Document oaiDoc = builder.parse(new ByteArrayInputStream(response.body()));

            XPath xpath = createXPath();
            Node codeBookNode = (Node) xpath.evaluate("//ddi:codeBook", oaiDoc, XPathConstants.NODE);
            if (codeBookNode == null)
                throw new IllegalArgumentException("No DDI codeBook found");

            Document ddiDoc = builder.newDocument();
            ddiDoc.appendChild(ddiDoc.importNode(codeBookNode, true));
            return ddiDoc;

        } catch (Exception e) {
            logSevere("Failed to parse XML. Preview: "
                    + new String(response.body(), 0, Math.min(500, response.body().length), StandardCharsets.UTF_8));
            throw new IOException("Failed to parse XML response", e);
        }
    }

    /**
     * Create an XPath instance with DDI namespace context.
     * 
     * @return XPath - the configured XPath with DDI namespace context
     */
    private XPath createXPath() {
        XPath xpath = xPathFactory.newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            public String getNamespaceURI(String prefix) {
                return "ddi".equals(prefix) ? DDI_NAMESPACE : null;
            }

            public String getPrefix(String namespaceURI) {
                return null;
            }

            public Iterator<String> getPrefixes(String namespaceURI) {
                return null;
            }
        });
        return xpath;
    }

    /**
     * Check the DDI document for approved PID schemas.
     * 
     * @param ddiDoc   The DDI document
     * @param recordId The record identifier (for logging)
     * @return "pass", "fail", or "indeterminate"
     */
    private String checkDocumentForApprovedPid(Document ddiDoc, String recordId) {
        try {
            XPath xpath = createXPath();
            NodeList idNoNodes = (NodeList) xpath.evaluate(DDI_SEARCH_PATH, ddiDoc, XPathConstants.NODESET);

            if (idNoNodes == null || idNoNodes.getLength() == 0) {
                logInfo("No IDNo elements found in DDI document for record: " + recordId);
                return RESULT_FAIL;
            }
            Set<String> approvedSchemas = getApprovedPidSchemas();
            for (int i = 0; i < idNoNodes.getLength(); i++) {
                Node idNoNode = idNoNodes.item(i);
                String agency = idNoNode.getAttributes().getNamedItem("agency").getNodeValue();
                if (approvedSchemas.contains(agency)) {
                    logInfo("Found approved PID schema '" + agency + "' in record: " + recordId);
                    return RESULT_PASS;
                }
            }
            logInfo("No approved PID schemas found in record: " + recordId);
            return RESULT_FAIL;
        } catch (Exception e) {
            logSevere("Error checking document for approved PID: " + e.getMessage());
            return RESULT_INDETERMINATE;
        }
    }

    /**
     * Fetch and cache the approved PID schemas from the CESSDA vocabulary.
     * 
     * @return Set of approved PID schema notations
     */
    private Set<String> getApprovedPidSchemas() {
        if (cachedApprovedSchemas != null && !cachedApprovedSchemas.isEmpty()) {
            return cachedApprovedSchemas;
        }

        synchronized (MetadataUsesCessdaPids.class) {
            if (cachedApprovedSchemas != null && !cachedApprovedSchemas.isEmpty()) {
                return cachedApprovedSchemas;
            }

            logInfo("Fetching approved PID schemas from CESSDA vocabulary...");
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(PID_VOCAB_URL))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() != 200) {
                    logSevere("PID vocabulary API returned " + response.statusCode());
                    return defaultPidSchemas();
                }

                JsonNode root = mapper.readTree(response.body());
                Set<String> schemas = new HashSet<>();

                JsonNode versions = root.path("versions");
                if (versions.isArray() && !versions.isEmpty()) {
                    JsonNode firstVersion = versions.get(0);
                    JsonNode concepts = firstVersion.path("concepts");

                    if (concepts.isArray()) {
                        for (JsonNode titleNode : concepts) {
                            String value = titleNode.path("title").asText(null);
                            if (value != null && !value.isBlank()) {
                                value = value.trim();
                                logInfo("Found PID schema entry: " + value);
                                schemas.add(value);
                            }
                        }
                    }
                }

                if (schemas.isEmpty()) {
                    logSevere("No valid PID schemas found in vocabulary response");
                    return defaultPidSchemas();
                }

                cachedApprovedSchemas = Collections.unmodifiableSet(schemas);
                logInfo("Fetched " + schemas.size() + " approved PID schemas: " + cachedApprovedSchemas);
                return cachedApprovedSchemas;

            } catch (Exception e) {
                Thread.currentThread().interrupt();
                logSevere("Failed to fetch PID vocabulary: " + e.getMessage());
                return defaultPidSchemas();
            }
        }
    }

    /**
     * Default PID schemas if vocabulary fetch fails.
     * 
     * @return Set of default PID schema notations
     */
    private static Set<String> defaultPidSchemas() {
        return Set.of("DOI", "Handle", "URN", "ARK");
    }

    /**
     * Log info messages, escaping % characters.
     * 
     * @param msg The message to log
     */
    static void logInfo(String msg) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info(msg.replace("%", "%%"));
        }
    }

    /**
     * Log severe messages, escaping % characters.
     * 
     * @param msg The message to log
     */
    static void logSevere(String msg) {
        if (logger.isLoggable(Level.SEVERE)) {
            logger.severe(msg.replace("%", "%%"));
        }
    }

    /**
     * Main method for command-line testing.
     * 
     * @param args Command-line arguments (expects a single CESSDA detail URL)
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            logSevere("Usage: java MetadataUsesCessdaPids <url>");
            System.exit(1);
        }

        MetadataUsesCessdaPids checker = new MetadataUsesCessdaPids();
        String result = checker.containsApprovedPid(args[0]);
        logInfo("Result: " + result);
        System.exit(result.equals(RESULT_PASS) ? 0 : 1);
    }
}
