package cessda.fairtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.w3c.dom.Document;

class MetadataUsesCessdaPidsTest {

    private MetadataUsesCessdaPids checker;
    private HttpClient mockHttpClient;
    private HttpResponse<String> mockStringResponse;
    private HttpResponse<byte[]> mockByteResponse;

    @BeforeEach
    void setUp() {
        checker = new MetadataUsesCessdaPids();
        mockHttpClient = mock(HttpClient.class);
        mockStringResponse = mockHttpResponse();
        mockByteResponse = mockHttpResponse();
    }

    @Test
    void testExtractRecordIdentifier_validUrl() throws Exception {
        var url = "https://datacatalogue.cessda.eu/detail/abc123?lang=en";
        var method = MetadataUsesCessdaPids.class.getDeclaredMethod("extractRecordIdentifier", String.class);
        method.setAccessible(true);
        assertEquals("abc123", method.invoke(checker, url));
    }

    @Test
    void testExtractRecordIdentifier_invalidUrl_throwsException() throws Exception {
        var method = MetadataUsesCessdaPids.class.getDeclaredMethod("extractRecordIdentifier", String.class);
        method.setAccessible(true);
        assertThrows(InvocationTargetException.class,
                () -> method.invoke(checker, "https://datacatalogue.cessda.eu/foo"));
    }

    @Test
    void testFetchAndParseDocument_validXml() throws Exception {
        String xml = "<ddi:codeBook xmlns:ddi='ddi:codebook:2_5'><ddi:stdyDscr><ddi:citation><ddi:titlStmt><ddi:IDNo agency='DOI'>10.123</ddi:IDNo></ddi:titlStmt></ddi:citation></ddi:stdyDscr></ddi:codeBook>";
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);

        when(mockByteResponse.statusCode()).thenReturn(200);
        when(mockByteResponse.body()).thenReturn(xmlBytes);
        when(mockHttpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(mockByteResponse);

        MetadataUsesCessdaPids localChecker = new MetadataUsesCessdaPids();
        var field = MetadataUsesCessdaPids.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(localChecker, mockHttpClient);

        Document doc = localChecker.fetchAndParseDocument("https://fakeurl.org/abc");
        assertNotNull(doc.getDocumentElement());
        assertEquals("codeBook", doc.getDocumentElement().getLocalName());
    }

    @Test
    void testFetchAndParseDocument_httpError() throws Exception {
        when(mockByteResponse.statusCode()).thenReturn(500);
        when(mockByteResponse.body()).thenReturn("".getBytes(StandardCharsets.UTF_8));
        when(mockHttpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(mockByteResponse);

        MetadataUsesCessdaPids localChecker = new MetadataUsesCessdaPids();
        var field = MetadataUsesCessdaPids.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(localChecker, mockHttpClient);

        assertThrows(Exception.class, () -> localChecker.fetchAndParseDocument("https://bad.url"));
    }

    @Test
    void testCheckDocumentForApprovedPid_passesWhenAgencyMatches() throws Exception {
        String xml = "<ddi:codeBook xmlns:ddi='ddi:codebook:2_5'>" +
                "<ddi:stdyDscr><ddi:citation><ddi:titlStmt>" +
                "<ddi:IDNo agency='DOI'>10.123</ddi:IDNo>" +
                "</ddi:titlStmt></ddi:citation></ddi:stdyDscr>" +
                "</ddi:codeBook>";
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var method = MetadataUsesCessdaPids.class.getDeclaredMethod("checkDocumentForApprovedPid", Document.class,
                String.class);
        method.setAccessible(true);

        try (MockedStatic<MetadataUsesCessdaPids> mocked = Mockito.mockStatic(MetadataUsesCessdaPids.class,
                Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> MetadataUsesCessdaPids.logInfo(anyString())).thenAnswer(inv -> null);
            mocked.when(() -> MetadataUsesCessdaPids.logSevere(anyString())).thenAnswer(inv -> null);
        }

        assertEquals("pass", method.invoke(checker, doc, "abc"));
    }

    @Test
    void testCheckDocumentForApprovedPid_failsWhenNoAgencyMatches() throws Exception {
        String xml = "<ddi:codeBook xmlns:ddi='ddi:codebook:2_5'>" +
                "<ddi:stdyDscr><ddi:citation><ddi:titlStmt>" +
                "<ddi:IDNo agency='XYZ'>999</ddi:IDNo>" +
                "</ddi:titlStmt></ddi:citation></ddi:stdyDscr>" +
                "</ddi:codeBook>";
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        var method = MetadataUsesCessdaPids.class.getDeclaredMethod("checkDocumentForApprovedPid", Document.class,
                String.class);
        method.setAccessible(true);
        assertEquals("fail", method.invoke(checker, doc, "abc"));
    }

    @Test
    void testGetApprovedPidSchemas_fromVocabulary() throws Exception {
        String json = """
                {
                  "versions": [{
                    "concepts": [
                      {"title": "ARK"},
                      {"title": "DOI"},
                      {"title": "Handle"},
                      {"title": "URN"}
                    ]
                  }]
                }
                """;

        when(mockStringResponse.statusCode()).thenReturn(200);
        when(mockStringResponse.body()).thenReturn(json);
        when(mockHttpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(mockStringResponse);

        MetadataUsesCessdaPids localChecker = new MetadataUsesCessdaPids();
        var field = MetadataUsesCessdaPids.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(localChecker, mockHttpClient);

        var method = MetadataUsesCessdaPids.class.getDeclaredMethod("getApprovedPidSchemas");
        method.setAccessible(true);
        Set<String> result = invokePrivate(localChecker, "getApprovedPidSchemas");
        assertTrue(result.contains("DOI"));
        assertTrue(result.size() >= 3);
    }

    @Test
    void testGetApprovedPidSchemas_defaultFallback() throws Exception {
        when(mockStringResponse.statusCode()).thenReturn(500);
        when(mockHttpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(mockStringResponse);

        MetadataUsesCessdaPids localChecker = new MetadataUsesCessdaPids();
        var field = MetadataUsesCessdaPids.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(localChecker, mockHttpClient);

        var method = MetadataUsesCessdaPids.class.getDeclaredMethod("getApprovedPidSchemas");
        method.setAccessible(true);
        Set<String> result = invokePrivate(localChecker, "getApprovedPidSchemas");
        assertTrue(result.contains("DOI"));
    }

    @Test
    void testMain_invocation() {
        try (MockedStatic<System> mockedSystem = mockStatic(System.class)) {
            mockedSystem.when(() -> System.exit(anyInt())).thenAnswer(inv -> null);
            MetadataUsesCessdaPids.main(new String[] { "https://datacatalogue.cessda.eu/detail/abc123" });

            // Verify System.exit was called with success code
            mockedSystem.verify(() -> System.exit(0));
        }
    }

    @Test
    void testDefaultPidSchemas_returnsExpectedDefaults() throws Exception {
        var method = MetadataUsesCessdaPids.class.getDeclaredMethod("defaultPidSchemas");
        method.setAccessible(true);
        Set<String> result = invokePrivate(null, "defaultPidSchemas");

        assertTrue(result.contains("DOI"));
        assertEquals(4, result.size());
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokePrivate(Object target, String methodName, Class<?>... paramTypes) throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return (T) method.invoke(target);
    }

    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> mockHttpResponse() {
        return (HttpResponse<T>) mock(HttpResponse.class);
    }

}
