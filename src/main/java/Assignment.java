import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Locale;

final class Assignment
{
    //Fields
    private final long courseId;
    private final String courseName;
    private final String assignmentName;
    private final ZonedDateTime dueAt;
    private final String htmlUrl;
    private final String gradingType;
    private final Double pointsPossible;
    private final String grade;
    private final Double score;
    private final boolean submitted;
    private final boolean graded;
    private final boolean missing;
    private final boolean late;
    private final boolean excused;

    //Constructors
    private Assignment(
            long courseId,
            String courseName,
            String assignmentName,
            ZonedDateTime dueAt,
            String htmlUrl,
            String gradingType,
            Double pointsPossible,
            String grade,
            Double score,
            boolean submitted,
            boolean graded,
            boolean missing,
            boolean late,
            boolean excused)
    {
        this.courseId = courseId;
        this.courseName = courseName;
        this.assignmentName = assignmentName;
        this.dueAt = dueAt;
        this.htmlUrl = htmlUrl;
        this.gradingType = gradingType;
        this.pointsPossible = pointsPossible;
        this.grade = grade;
        this.score = score;
        this.submitted = submitted;
        this.graded = graded;
        this.missing = missing;
        this.late = late;
        this.excused = excused;
    }

    //Helpers
    static Assignment fromJson(long courseId, String courseName, JsonNode assignmentNode)
    {
        String assignmentName = textValue(assignmentNode, "name");
        if (assignmentName == null || assignmentName.isBlank())
        {
            return null;
        }

        JsonNode submissionNode = assignmentNode.path("submission");
        String workflowState = textValue(submissionNode, "workflow_state");
        String submittedAt = textValue(submissionNode, "submitted_at");
        boolean excused = booleanValue(submissionNode, "excused");
        boolean missing = booleanValue(submissionNode, "missing");
        boolean late = booleanValue(submissionNode, "late");
        boolean graded = "graded".equalsIgnoreCase(workflowState);
        boolean submitted = graded
                || hasText(submittedAt)
                || matchesAny(workflowState, "submitted", "pending_review", "complete");

        return new Assignment(
                courseId,
                courseName,
                assignmentName,
                parseDateTime(textValue(assignmentNode, "due_at")),
                textValue(assignmentNode, "html_url"),
                textValue(assignmentNode, "grading_type"),
                doubleValue(assignmentNode, "points_possible"),
                firstNonBlank(
                        textValue(submissionNode, "grade"),
                        textValue(submissionNode, "entered_grade")),
                doubleValue(submissionNode, "score"),
                submitted,
                graded,
                missing,
                late,
                excused);
    }

    private static ZonedDateTime parseDateTime(String value)
    {
        if (!hasText(value))
        {
            return null;
        }

        try
        {
            return OffsetDateTime.parse(value).toZonedDateTime();
        }
        catch (Exception exception)
        {
            return null;
        }
    }

    private static String textValue(JsonNode node, String fieldName)
    {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(fieldName))
        {
            return null;
        }
        return node.get(fieldName).asText();
    }

    private static Double doubleValue(JsonNode node, String fieldName)
    {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(fieldName))
        {
            return null;
        }
        return node.get(fieldName).asDouble();
    }

    private static boolean booleanValue(JsonNode node, String fieldName)
    {
        return node != null && !node.isMissingNode() && node.has(fieldName) && node.get(fieldName).asBoolean(false);
    }

    private static boolean matchesAny(String value, String... candidates)
    {
        if (!hasText(value))
        {
            return false;
        }

        String lowered = value.toLowerCase(Locale.US);
        for (String candidate : candidates)
        {
            if (lowered.equals(candidate))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values)
    {
        for (String value : values)
        {
            if (hasText(value))
            {
                return value.trim();
            }
        }
        return null;
    }

    private static String formatNumber(double value)
    {
        if (Math.abs(value - Math.rint(value)) < 0.0000001d)
        {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.US, "%.2f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private String formatPointsGrade()
    {
        if (score == null)
        {
            return "n/a";
        }
        if (pointsPossible == null)
        {
            return formatNumber(score);
        }
        return formatNumber(score) + "/" + formatNumber(pointsPossible);
    }

    private String formatPercentGrade()
    {
        if (hasText(grade))
        {
            String trimmed = grade.trim();
            return trimmed.endsWith("%") ? trimmed : trimmed + "%";
        }
        if (score == null)
        {
            return "n/a";
        }
        return formatNumber(score) + "%";
    }

    private String formatDefaultGrade()
    {
        if (hasText(grade))
        {
            return grade.trim();
        }
        if (score == null)
        {
            return "n/a";
        }
        if (pointsPossible != null)
        {
            return formatNumber(score) + "/" + formatNumber(pointsPossible);
        }
        return formatNumber(score);
    }

    //Methods
    long getCourseId()
    {
        return courseId;
    }

    String getCourseName()
    {
        return courseName;
    }

    String getAssignmentName()
    {
        return assignmentName;
    }

    ZonedDateTime getDueAt()
    {
        return dueAt;
    }

    String getHtmlUrl()
    {
        return htmlUrl;
    }

    boolean hasDueDate()
    {
        return dueAt != null;
    }

    ZonedDateTime getDueAtIn(java.time.ZoneId zoneId)
    {
        if (dueAt == null)
        {
            return null;
        }
        return dueAt.withZoneSameInstant(zoneId);
    }

    LocalDate getDueDateIn(java.time.ZoneId zoneId)
    {
        ZonedDateTime dueAtInZone = getDueAtIn(zoneId);
        return dueAtInZone == null ? null : dueAtInZone.toLocalDate();
    }

    boolean isDueBetween(LocalDate afterDate, LocalDate beforeDate, java.time.ZoneId zoneId)
    {
        LocalDate dueDate = getDueDateIn(zoneId);
        if (dueDate == null)
        {
            return false;
        }
        return !dueDate.isBefore(afterDate) && !dueDate.isAfter(beforeDate);
    }

    boolean isDueOnOrBefore(LocalDate beforeDate, java.time.ZoneId zoneId)
    {
        LocalDate dueDate = getDueDateIn(zoneId);
        return dueDate != null && !dueDate.isAfter(beforeDate);
    }

    boolean isCompleted()
    {
        return submitted || graded || excused;
    }

    boolean shouldInclude(boolean hideSubmitted)
    {
        return !hideSubmitted || !isCompleted();
    }

    String getStatusLabel(ZonedDateTime now, java.time.ZoneId zoneId)
    {
        if (excused)
        {
            return "excused";
        }
        if (graded)
        {
            return "graded";
        }
        if (missing)
        {
            return "missing";
        }
        if (late)
        {
            return "late";
        }
        if (submitted)
        {
            return "submitted";
        }

        ZonedDateTime dueAtInZone = getDueAtIn(zoneId);
        if (dueAtInZone != null && dueAtInZone.isBefore(now))
        {
            return "overdue";
        }
        return "pending";
    }

    String getGradeLabel()
    {
        if (!graded)
        {
            return "n/a";
        }

        String normalizedType = gradingType == null ? "" : gradingType.trim().toLowerCase(Locale.US);
        return switch (normalizedType)
        {
            case "points" -> formatPointsGrade();
            case "percent" -> formatPercentGrade();
            default -> formatDefaultGrade();
        };
    }
}
