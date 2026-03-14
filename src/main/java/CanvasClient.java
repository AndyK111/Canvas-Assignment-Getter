import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class CanvasClient
{
    //Fields
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final String token;
    private final Consumer<String> progressSink;

    //Constructors
    CanvasClient(String baseUrl, String token, Consumer<String> progressSink)
    {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.objectMapper = new ObjectMapper();
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.token = token;
        this.progressSink = progressSink == null ? message ->
        {
        } : progressSink;
    }

    //Helpers
    private List<CourseInfo> fetchActiveCourses() throws CanvasException
    {
        List<JsonNode> courseNodes = requestArrayPages(
                "/api/v1/courses?enrollment_state=active&state[]=available&per_page=100",
                "courses");
        List<CourseInfo> courses = new ArrayList<>();

        for (JsonNode courseNode : courseNodes)
        {
            if (!courseNode.hasNonNull("id") || !courseNode.hasNonNull("name"))
            {
                continue;
            }

            long courseId = courseNode.get("id").asLong();
            String courseName = courseNode.get("name").asText();
            String courseCode = courseNode.hasNonNull("course_code") ? courseNode.get("course_code").asText() : null;
            String displayName = courseCode != null && !courseCode.isBlank() ? courseCode : courseName;
            courses.add(new CourseInfo(courseId, displayName));
        }

        return courses;
    }

    private List<Assignment> fetchAssignmentsForCourse(CourseInfo courseInfo) throws CanvasException
    {
        String encodedCourseId = URLEncoder.encode(Long.toString(courseInfo.courseId), StandardCharsets.UTF_8);
        String path = "/api/v1/courses/" + encodedCourseId
                + "/assignments?include[]=submission&order_by=due_at&per_page=100";
        List<JsonNode> assignmentNodes = requestArrayPages(path, "assignments for " + courseInfo.displayName);
        List<Assignment> assignments = new ArrayList<>();

        for (JsonNode assignmentNode : assignmentNodes)
        {
            Assignment assignment = Assignment.fromJson(courseInfo.courseId, courseInfo.displayName, assignmentNode);
            if (assignment != null)
            {
                assignments.add(assignment);
            }
        }

        return assignments;
    }

    private List<JsonNode> requestArrayPages(String pathOrUrl, String label) throws CanvasException
    {
        List<JsonNode> items = new ArrayList<>();
        URI nextUri = resolve(pathOrUrl);
        int pageNumber = 1;

        while (nextUri != null)
        {
            progressSink.accept("Requesting " + label + " page " + pageNumber + "...");
            HttpResponse<String> response = send(nextUri);
            JsonNode rootNode = parseJson(response.body());
            if (!rootNode.isArray())
            {
                throw new CanvasException("Canvas returned an unexpected payload shape.");
            }

            for (JsonNode itemNode : rootNode)
            {
                items.add(itemNode);
            }

            nextUri = parseNextLink(response.headers());
            pageNumber += 1;
        }

        return items;
    }

    private JsonNode parseJson(String body) throws CanvasException
    {
        try
        {
            return objectMapper.readTree(body);
        }
        catch (IOException exception)
        {
            throw new CanvasException("Unable to parse Canvas JSON: " + exception.getMessage(), exception);
        }
    }

    private HttpResponse<String> send(URI uri) throws CanvasException
    {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("User-Agent", "canvas-assignment-cli")
                .GET()
                .build();

        try
        {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300)
            {
                return response;
            }
            throw createHttpError(statusCode, response.body());
        }
        catch (IOException exception)
        {
            throw new CanvasException("Network error while calling Canvas: " + exception.getMessage(), exception);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new CanvasException("Request interrupted while calling Canvas.", exception);
        }
    }

    private CanvasException createHttpError(int statusCode, String body)
    {
        String detail = extractErrorDetail(body);
        return switch (statusCode)
        {
            case 401, 403 -> new CanvasException(
                    "Canvas rejected the request (HTTP " + statusCode + "). Check your token and base URL. " + detail);
            case 404 -> new CanvasException("Canvas endpoint not found (HTTP 404). Check the base URL. " + detail);
            default -> new CanvasException("Canvas returned HTTP " + statusCode + ". " + detail);
        };
    }

    private String extractErrorDetail(String body)
    {
        if (body == null || body.isBlank())
        {
            return "No error body was returned.";
        }

        try
        {
            JsonNode rootNode = objectMapper.readTree(body);
            if (rootNode.has("errors"))
            {
                return "Details: " + rootNode.get("errors").toString();
            }
            if (rootNode.has("message"))
            {
                return "Details: " + rootNode.get("message").asText();
            }
            if (rootNode.has("error"))
            {
                return "Details: " + rootNode.get("error").asText();
            }
        }
        catch (IOException ignored)
        {
            // Use the raw body when the error payload is not JSON.
        }

        return "Details: " + body.replaceAll("\\s+", " ").trim();
    }

    private URI resolve(String pathOrUrl)
    {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://"))
        {
            return URI.create(pathOrUrl);
        }
        return baseUri.resolve(pathOrUrl.startsWith("/") ? pathOrUrl.substring(1) : pathOrUrl);
    }

    private URI parseNextLink(HttpHeaders headers)
    {
        List<String> linkHeaders = headers.allValues("Link");
        for (String linkHeader : linkHeaders)
        {
            for (String section : linkHeader.split(","))
            {
                String[] parts = section.trim().split(";");
                if (parts.length < 2)
                {
                    continue;
                }

                String urlPart = parts[0].trim();
                String relPart = parts[1].trim();
                if (!relPart.contains("rel=\"next\""))
                {
                    continue;
                }

                if (urlPart.startsWith("<") && urlPart.endsWith(">"))
                {
                    return URI.create(urlPart.substring(1, urlPart.length() - 1));
                }
            }
        }

        return null;
    }

    //Methods
    List<Assignment> fetchAssignments() throws CanvasException
    {
        progressSink.accept("Loading active courses...");
        List<CourseInfo> courses = fetchActiveCourses();
        progressSink.accept("Found " + courses.size() + " active course(s).");

        List<Assignment> assignments = new ArrayList<>();
        for (int index = 0; index < courses.size(); index++)
        {
            CourseInfo courseInfo = courses.get(index);
            progressSink.accept("[" + (index + 1) + "/" + courses.size() + "] Loading assignments for "
                    + courseInfo.displayName + "...");
            assignments.addAll(fetchAssignmentsForCourse(courseInfo));
        }

        progressSink.accept("Fetched " + assignments.size() + " assignment(s) from Canvas.");
        return assignments;
    }

    private static final class CourseInfo
    {
        private final long courseId;
        private final String displayName;

        private CourseInfo(long courseId, String displayName)
        {
            this.courseId = courseId;
            this.displayName = displayName;
        }
    }
}
