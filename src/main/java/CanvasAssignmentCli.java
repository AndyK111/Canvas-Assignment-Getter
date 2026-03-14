import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CanvasAssignmentCli
{
    //Fields
    private static final DateTimeFormatter DAY_HEADER_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM d");
    private static final DateTimeFormatter DATE_COLUMN_FORMAT =
            DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
    private static final int TIME_WIDTH = 8;
    private static final int DATE_WIDTH = 8;
    private static final int COURSE_WIDTH = 18;
    private static final int ASSIGNMENT_WIDTH = 34;
    private static final int STATUS_WIDTH = 14;
    private static final int GRADE_WIDTH = 14;
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLACK_ON_WHITE = "\u001B[30;47m";
    private static final long DUE_COLOR_MAX_HOURS = 24L * 7L;

    //Constructors
    public static void main(String[] args)
    {
        int exitCode = new CanvasAssignmentCli().run(args);
        if (exitCode != 0)
        {
            System.exit(exitCode);
        }
    }

    //Helpers
    private int run(String[] args)
    {
        try
        {
            ArgumentConfig config = ArgumentConfig.fromArgs(args);
            if (config.isHelpRequested())
            {
                System.out.println(ArgumentConfig.usage());
                return 0;
            }

            if (config.getToken() == null || config.getToken().isBlank())
            {
                System.err.println("Missing CANVAS_API_TOKEN. Add it to your environment or a local .env file.");
                return 1;
            }

            progress(config, "Starting Canvas assignment fetch...");
            CanvasClient client = new CanvasClient(
                    config.getBaseUrl(),
                    config.getToken(),
                    message -> progress(config, message));

            List<Assignment> assignments = client.fetchAssignments();
            List<Assignment> selectedAssignments = selectAssignments(assignments, config);
            sortAssignmentsForDisplay(selectedAssignments, config);
            progress(config, "Rendering " + selectedAssignments.size() + " assignment(s).");
            renderAssignments(selectedAssignments, config);
            return 0;
        }
        catch (UserInputException exception)
        {
            System.err.println(exception.getMessage());
            System.err.println();
            System.err.println(ArgumentConfig.usage());
            return 2;
        }
        catch (CanvasException exception)
        {
            System.err.println(exception.getMessage());
            return 1;
        }
    }

    private List<Assignment> selectAssignments(List<Assignment> assignments, ArgumentConfig config)
    {
        List<Assignment> filteredAssignments = new ArrayList<>();

        for (Assignment assignment : assignments)
        {
            if (!assignment.hasDueDate())
            {
                continue;
            }

            if (!assignment.shouldInclude(config.isHideSubmitted()))
            {
                continue;
            }

            if (config.getMode() == ArgumentConfig.Mode.DATE || config.getMode() == ArgumentConfig.Mode.WEEKLY)
            {
                if (assignment.isDueBetween(config.getAfterDate(), config.getBeforeDate(), config.getZoneId()))
                {
                    filteredAssignments.add(assignment);
                }
                continue;
            }

            if (config.getMode() == ArgumentConfig.Mode.QUANTITY
                    && assignment.isDueOnOrBefore(config.getBeforeDate(), config.getZoneId()))
            {
                filteredAssignments.add(assignment);
            }
        }

        if (config.getMode() == ArgumentConfig.Mode.QUANTITY)
        {
            filteredAssignments.sort(Comparator
                    .comparing((Assignment assignment) -> assignment.getDueAtIn(config.getZoneId()))
                    .reversed()
                    .thenComparing(Assignment::getCourseName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Assignment::getAssignmentName, String.CASE_INSENSITIVE_ORDER));

            if (filteredAssignments.size() > config.getQuantity())
            {
                return new ArrayList<>(filteredAssignments.subList(0, config.getQuantity()));
            }
        }

        return filteredAssignments;
    }

    private void sortAssignmentsForDisplay(List<Assignment> assignments, ArgumentConfig config)
    {
        Comparator<Assignment> dueComparator = Comparator.comparing(assignment -> assignment.getDueAtIn(config.getZoneId()));
        if (config.getMode() == ArgumentConfig.Mode.QUANTITY)
        {
            dueComparator = dueComparator.reversed();
        }

        Comparator<Assignment> comparator;
        if (config.getGroupBy() == ArgumentConfig.GroupBy.CLASS)
        {
            comparator = Comparator.comparing(Assignment::getCourseName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(dueComparator)
                    .thenComparing(Assignment::getAssignmentName, String.CASE_INSENSITIVE_ORDER);
        }
        else
        {
            comparator = dueComparator
                    .thenComparing(Assignment::getCourseName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Assignment::getAssignmentName, String.CASE_INSENSITIVE_ORDER);
        }

        assignments.sort(comparator);
    }

    private void renderAssignments(List<Assignment> assignments, ArgumentConfig config)
    {
        printReportHeader(config, assignments.size());

        if (assignments.isEmpty())
        {
            System.out.println("No matching assignments found.");
            return;
        }

        if (config.getGroupBy() == ArgumentConfig.GroupBy.CLASS)
        {
            renderGroupedByClass(assignments, config);
        }
        else
        {
            renderGroupedByDate(assignments, config);
        }
    }

    private void printReportHeader(ArgumentConfig config, int count)
    {
        if (config.getMode() == ArgumentConfig.Mode.QUANTITY)
        {
            System.out.printf(
                    "Assignments on or before %s (%s)%n",
                    config.getBeforeDate(),
                    config.getZoneId().getId());
            System.out.println("Showing up to " + config.getQuantity() + " assignment(s).");
        }
        else
        {
            System.out.printf(
                    "Assignments from %s to %s (%s)%n",
                    config.getAfterDate(),
                    config.getBeforeDate(),
                    config.getZoneId().getId());
            System.out.println("Showing " + count + " assignment(s).");
        }

        System.out.println("Grouped by " + config.getGroupBy().name().toLowerCase() + ".");
        if (config.isHideSubmitted())
        {
            System.out.println("Submitted, graded, and excused work is hidden.");
        }
        System.out.println();
    }

    private void renderGroupedByDate(List<Assignment> assignments, ArgumentConfig config)
    {
        String headerFormat = buildDateGroupFormat(config.isShowGrades());
        int tableWidth = dateGroupTableWidth(config.isShowGrades());
        LocalDate currentDate = null;
        ZonedDateTime now = ZonedDateTime.now(config.getZoneId());

        for (Assignment assignment : assignments)
        {
            LocalDate assignmentDate = assignment.getDueDateIn(config.getZoneId());
            if (!assignmentDate.equals(currentDate))
            {
                currentDate = assignmentDate;
                System.out.println(formatDateBanner(assignmentDate.format(DAY_HEADER_FORMAT), tableWidth));
                printDateGroupHeaders(headerFormat, config.isShowGrades());
            }

            printDateGroupRow(headerFormat, assignment, config, now);
        }
    }

    private void renderGroupedByClass(List<Assignment> assignments, ArgumentConfig config)
    {
        String headerFormat = buildClassGroupFormat(config.isShowGrades());
        int tableWidth = classGroupTableWidth(config.isShowGrades());
        String currentCourse = null;
        ZonedDateTime now = ZonedDateTime.now(config.getZoneId());

        for (Assignment assignment : assignments)
        {
            if (!assignment.getCourseName().equals(currentCourse))
            {
                currentCourse = assignment.getCourseName();
                System.out.println(center(currentCourse, tableWidth));
                printClassGroupHeaders(headerFormat, config.isShowGrades());
            }

            printClassGroupRow(headerFormat, assignment, config, now);
        }
    }

    private void printDateGroupHeaders(String format, boolean showGrades)
    {
        if (showGrades)
        {
            System.out.printf(format, "Time", "Course", "Assignment", "Status", "Grade");
            System.out.printf(
                    format,
                    repeat('-', TIME_WIDTH),
                    repeat('-', COURSE_WIDTH),
                    repeat('-', ASSIGNMENT_WIDTH),
                    repeat('-', STATUS_WIDTH),
                    repeat('-', GRADE_WIDTH));
            return;
        }

        System.out.printf(format, "Time", "Course", "Assignment", "Status");
        System.out.printf(
                format,
                repeat('-', TIME_WIDTH),
                repeat('-', COURSE_WIDTH),
                repeat('-', ASSIGNMENT_WIDTH),
                repeat('-', STATUS_WIDTH));
    }

    private void printClassGroupHeaders(String format, boolean showGrades)
    {
        if (showGrades)
        {
            System.out.printf(format, "Date", "Time", "Assignment", "Status", "Grade");
            System.out.printf(
                    format,
                    repeat('-', DATE_WIDTH),
                    repeat('-', TIME_WIDTH),
                    repeat('-', ASSIGNMENT_WIDTH),
                    repeat('-', STATUS_WIDTH),
                    repeat('-', GRADE_WIDTH));
            return;
        }

        System.out.printf(format, "Date", "Time", "Assignment", "Status");
        System.out.printf(
                format,
                repeat('-', DATE_WIDTH),
                repeat('-', TIME_WIDTH),
                repeat('-', ASSIGNMENT_WIDTH),
                repeat('-', STATUS_WIDTH));
    }

    private void printDateGroupRow(
            String format,
            Assignment assignment,
            ArgumentConfig config,
            ZonedDateTime now)
    {
        ZonedDateTime dueAt = assignment.getDueAtIn(config.getZoneId());
        String timeCell = colorizeDueValue(
                formatCell(dueAt.format(TIME_FORMAT), TIME_WIDTH),
                dueAt,
                now);
        String courseCell = formatCell(truncate(assignment.getCourseName(), COURSE_WIDTH), COURSE_WIDTH);
        String assignmentCell = formatCell(truncate(assignment.getAssignmentName(), ASSIGNMENT_WIDTH), ASSIGNMENT_WIDTH);
        String statusLabel = truncate(assignment.getStatusLabel(now, config.getZoneId()), STATUS_WIDTH);
        String statusCell = colorizeStatus(formatCell(statusLabel, STATUS_WIDTH), statusLabel);

        if (config.isShowGrades())
        {
            String gradeCell = formatCell(truncate(assignment.getGradeLabel(), GRADE_WIDTH), GRADE_WIDTH);
            System.out.println(buildRow(timeCell, courseCell, assignmentCell, statusCell, gradeCell));
            return;
        }

        System.out.println(buildRow(timeCell, courseCell, assignmentCell, statusCell));
    }

    private void printClassGroupRow(
            String format,
            Assignment assignment,
            ArgumentConfig config,
            ZonedDateTime now)
    {
        ZonedDateTime dueAt = assignment.getDueAtIn(config.getZoneId());
        String dateCell = colorizeDueValue(
                formatCell(dueAt.toLocalDate().format(DATE_COLUMN_FORMAT), DATE_WIDTH),
                dueAt,
                now);
        String timeCell = colorizeDueValue(
                formatCell(dueAt.format(TIME_FORMAT), TIME_WIDTH),
                dueAt,
                now);
        String assignmentCell = formatCell(truncate(assignment.getAssignmentName(), ASSIGNMENT_WIDTH), ASSIGNMENT_WIDTH);
        String statusLabel = truncate(assignment.getStatusLabel(now, config.getZoneId()), STATUS_WIDTH);
        String statusCell = colorizeStatus(formatCell(statusLabel, STATUS_WIDTH), statusLabel);

        if (config.isShowGrades())
        {
            String gradeCell = formatCell(truncate(assignment.getGradeLabel(), GRADE_WIDTH), GRADE_WIDTH);
            System.out.println(buildRow(dateCell, timeCell, assignmentCell, statusCell, gradeCell));
            return;
        }

        System.out.println(buildRow(dateCell, timeCell, assignmentCell, statusCell));
    }

    private String buildDateGroupFormat(boolean showGrades)
    {
        if (showGrades)
        {
            return "%-" + TIME_WIDTH + "s  %-" + COURSE_WIDTH + "s  %-" + ASSIGNMENT_WIDTH + "s  %-"
                    + STATUS_WIDTH + "s  %-" + GRADE_WIDTH + "s%n";
        }
        return "%-" + TIME_WIDTH + "s  %-" + COURSE_WIDTH + "s  %-" + ASSIGNMENT_WIDTH + "s  %-"
                + STATUS_WIDTH + "s%n";
    }

    private String buildClassGroupFormat(boolean showGrades)
    {
        if (showGrades)
        {
            return "%-" + DATE_WIDTH + "s  %-" + TIME_WIDTH + "s  %-" + ASSIGNMENT_WIDTH + "s  %-"
                    + STATUS_WIDTH + "s  %-" + GRADE_WIDTH + "s%n";
        }
        return "%-" + DATE_WIDTH + "s  %-" + TIME_WIDTH + "s  %-" + ASSIGNMENT_WIDTH + "s  %-"
                + STATUS_WIDTH + "s%n";
    }

    private int dateGroupTableWidth(boolean showGrades)
    {
        int width = TIME_WIDTH + 2 + COURSE_WIDTH + 2 + ASSIGNMENT_WIDTH + 2 + STATUS_WIDTH;
        if (showGrades)
        {
            width += 2 + GRADE_WIDTH;
        }
        return width;
    }

    private int classGroupTableWidth(boolean showGrades)
    {
        int width = DATE_WIDTH + 2 + TIME_WIDTH + 2 + ASSIGNMENT_WIDTH + 2 + STATUS_WIDTH;
        if (showGrades)
        {
            width += 2 + GRADE_WIDTH;
        }
        return width;
    }

    private static void progress(ArgumentConfig config, String message)
    {
        if (config.isShowProgress())
        {
            System.err.println("[progress] " + message);
        }
    }

    private static String repeat(char value, int count)
    {
        return String.valueOf(value).repeat(Math.max(0, count));
    }

    private static String formatDateBanner(String value, int width)
    {
        return ANSI_BLACK_ON_WHITE + centerAndPad(value, width) + ANSI_RESET;
    }

    private static String centerAndPad(String value, int width)
    {
        String safeValue = value == null ? "" : value;
        if (safeValue.length() >= width)
        {
            return safeValue;
        }

        int leftPadding = (width - safeValue.length()) / 2;
        int rightPadding = width - safeValue.length() - leftPadding;
        return " ".repeat(leftPadding) + safeValue + " ".repeat(rightPadding);
    }

    private static String center(String value, int width)
    {
        if (value == null || value.length() >= width)
        {
            return value == null ? "" : value;
        }
        int leftPadding = (width - value.length()) / 2;
        return " ".repeat(leftPadding) + value;
    }

    private static String truncate(String value, int maxWidth)
    {
        if (value == null || value.length() <= maxWidth)
        {
            return value == null ? "" : value;
        }
        if (maxWidth <= 3)
        {
            return value.substring(0, maxWidth);
        }
        return value.substring(0, maxWidth - 3) + "...";
    }

    private static String formatCell(String value, int width)
    {
        String safeValue = value == null ? "" : value;
        if (safeValue.length() >= width)
        {
            return safeValue;
        }
        return safeValue + " ".repeat(width - safeValue.length());
    }

    private static String buildRow(String... cells)
    {
        return String.join("  ", cells);
    }

    private static String colorizeStatus(String value, String statusLabel)
    {
        return switch (statusLabel)
        {
            case "graded" -> ANSI_GREEN + value + ANSI_RESET;
            case "missing" -> ANSI_RED + value + ANSI_RESET;
            case "late" -> ANSI_YELLOW + value + ANSI_RESET;
            default -> value;
        };
    }

    private static String colorizeDueValue(String value, ZonedDateTime dueAt, ZonedDateTime now)
    {
        long hoursUntilDue = Duration.between(now, dueAt).toHours();
        double clampedRatio = Math.max(0.0d, Math.min(1.0d, hoursUntilDue / (double) DUE_COLOR_MAX_HOURS));
        int greenBlueValue = (int) Math.round(96 + ((255 - 96) * clampedRatio));
        return ansiRgb(255, greenBlueValue, greenBlueValue) + value + ANSI_RESET;
    }

    private static String ansiRgb(int red, int green, int blue)
    {
        return "\u001B[38;2;" + red + ";" + green + ";" + blue + "m";
    }
}
