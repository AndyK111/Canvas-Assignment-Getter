import io.github.cdimascio.dotenv.Dotenv;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

final class ArgumentConfig
{
    enum Mode
    {
        DATE,
        QUANTITY,
        WEEKLY
    }

    enum GroupBy
    {
        DATE,
        CLASS
    }

    //Fields
    private final String baseUrl;
    private final String token;
    private final ZoneId zoneId;
    private final Mode mode;
    private final GroupBy groupBy;
    private final LocalDate afterDate;
    private final LocalDate beforeDate;
    private final LocalDate whiteDate;
    private final int quantity;
    private final boolean showGrades;
    private final boolean showProgress;
    private final boolean hideSubmitted;
    private final boolean helpRequested;

    //Constructors
    private ArgumentConfig(
            String baseUrl,
            String token,
            ZoneId zoneId,
            Mode mode,
            GroupBy groupBy,
            LocalDate afterDate,
            LocalDate beforeDate,
            LocalDate whiteDate,
            int quantity,
            boolean showGrades,
            boolean showProgress,
            boolean hideSubmitted,
            boolean helpRequested)
    {
        this.baseUrl = baseUrl;
        this.token = token;
        this.zoneId = zoneId;
        this.mode = mode;
        this.groupBy = groupBy;
        this.afterDate = afterDate;
        this.beforeDate = beforeDate;
        this.whiteDate = whiteDate;
        this.quantity = quantity;
        this.showGrades = showGrades;
        this.showProgress = showProgress;
        this.hideSubmitted = hideSubmitted;
        this.helpRequested = helpRequested;
    }

    //Helpers
    static ArgumentConfig fromArgs(String[] args) throws UserInputException
    {
        Dotenv dotenv = Dotenv.configure()
                .directory(".")
                .ignoreIfMissing()
                .load();

        String baseUrl = firstNonBlank(
                System.getenv("CANVAS_BASE_URL"),
                dotenv.get("CANVAS_BASE_URL"),
                "https://boisestate.instructure.com");
        String token = firstNonBlank(
                System.getenv("CANVAS_API_TOKEN"),
                dotenv.get("CANVAS_API_TOKEN"));
        String zoneValue = firstNonBlank(
                System.getenv("CANVAS_TIME_ZONE"),
                dotenv.get("CANVAS_TIME_ZONE"),
                ZoneId.systemDefault().getId());

        ZoneId zoneId = parseZoneId(zoneValue);
        Mode mode = Mode.WEEKLY;
        GroupBy groupBy = GroupBy.DATE;
        LocalDate afterDate = null;
        LocalDate beforeDate = null;
        LocalDate whiteDate = null;
        int quantity = 0;
        boolean showGrades = false;
        boolean showProgress = false;
        boolean hideSubmitted = false;
        boolean helpRequested = false;
        int index = 0;

        if (args.length > 0 && !args[0].startsWith("--"))
        {
            mode = parseMode(args[0]);
            index = 1;
        }

        while (index < args.length)
        {
            String arg = args[index];
            switch (arg)
            {
                case "--help":
                case "-h":
                    helpRequested = true;
                    index += 1;
                    break;
                case "--base-url":
                    baseUrl = requireValue(args, index, arg);
                    index += 2;
                    break;
                case "--timezone":
                    zoneId = parseZoneId(requireValue(args, index, arg));
                    index += 2;
                    break;
                case "--after":
                    afterDate = parseDate(requireValue(args, index, arg), arg);
                    index += 2;
                    break;
                case "--before":
                    beforeDate = parseDate(requireValue(args, index, arg), arg);
                    index += 2;
                    break;
                case "--white-date":
                    whiteDate = parseDate(requireValue(args, index, arg), arg);
                    index += 2;
                    break;
                case "--quantity":
                    quantity = parseQuantity(requireValue(args, index, arg));
                    index += 2;
                    break;
                case "--group-by":
                    groupBy = parseGroupBy(requireValue(args, index, arg));
                    index += 2;
                    break;
                case "--show-grades":
                    showGrades = true;
                    index += 1;
                    break;
                case "--show-progress":
                    showProgress = true;
                    index += 1;
                    break;
                case "--hide-submitted":
                    hideSubmitted = true;
                    index += 1;
                    break;
                default:
                    throw new UserInputException("Unknown option: " + arg);
            }
        }

        LocalDate today = LocalDate.now(zoneId);
        if (whiteDate == null)
        {
            whiteDate = today.plusDays(30L);
        }
        if (mode == Mode.WEEKLY)
        {
            afterDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            beforeDate = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        }

        validateArguments(mode, afterDate, beforeDate, whiteDate, quantity, today);

        return new ArgumentConfig(
                normalizeBaseUrl(baseUrl),
                token,
                zoneId,
                mode,
                groupBy,
                afterDate,
                beforeDate,
                whiteDate,
                quantity,
                showGrades,
                showProgress,
                hideSubmitted,
                helpRequested);
    }

    private static void validateArguments(
            Mode mode,
            LocalDate afterDate,
            LocalDate beforeDate,
            LocalDate whiteDate,
            int quantity,
            LocalDate today)
            throws UserInputException
    {
        if (mode == Mode.DATE)
        {
            if (afterDate == null || beforeDate == null)
            {
                throw new UserInputException("Date mode requires both --after and --before.");
            }
        }

        if (mode == Mode.QUANTITY)
        {
            if (beforeDate == null)
            {
                throw new UserInputException("Quantity mode requires --before.");
            }
            if (quantity <= 0)
            {
                throw new UserInputException("Quantity mode requires a positive --quantity value.");
            }
        }

        if (afterDate != null && beforeDate != null && beforeDate.isBefore(afterDate))
        {
            throw new UserInputException("--before must be on or after --after.");
        }

        if (whiteDate != null && !whiteDate.isAfter(today))
        {
            throw new UserInputException("--white-date must be after today.");
        }
    }

    private static Mode parseMode(String value) throws UserInputException
    {
        return switch (value.toLowerCase())
        {
            case "date" -> Mode.DATE;
            case "quantity" -> Mode.QUANTITY;
            case "weekly" -> Mode.WEEKLY;
            default -> throw new UserInputException("Unknown mode: " + value);
        };
    }

    private static GroupBy parseGroupBy(String value) throws UserInputException
    {
        return switch (value.toLowerCase())
        {
            case "date" -> GroupBy.DATE;
            case "class" -> GroupBy.CLASS;
            default -> throw new UserInputException("Group by must be date or class.");
        };
    }

    private static ZoneId parseZoneId(String value) throws UserInputException
    {
        try
        {
            return ZoneId.of(value);
        }
        catch (Exception exception)
        {
            throw new UserInputException("Invalid timezone: " + value);
        }
    }

    private static LocalDate parseDate(String value, String option) throws UserInputException
    {
        try
        {
            return LocalDate.parse(value);
        }
        catch (Exception exception)
        {
            throw new UserInputException("Invalid date for " + option + ": " + value + ". Use YYYY-MM-DD.");
        }
    }

    private static int parseQuantity(String value) throws UserInputException
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException exception)
        {
            throw new UserInputException("Quantity must be a number: " + value);
        }
    }

    private static String requireValue(String[] args, int index, String option) throws UserInputException
    {
        if (index + 1 >= args.length)
        {
            throw new UserInputException("Missing value for " + option);
        }
        return args[index + 1];
    }

    private static String normalizeBaseUrl(String value)
    {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.endsWith("/"))
        {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String firstNonBlank(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isBlank())
            {
                return value.trim();
            }
        }
        return null;
    }

    //Methods
    static String usage()
    {
        return """
                Usage:
                  canvas-assignment-cli [weekly] [options]
                  canvas-assignment-cli date --after YYYY-MM-DD --before YYYY-MM-DD [options]
                  canvas-assignment-cli quantity --before YYYY-MM-DD --quantity N [options]

                Modes:
                  weekly                  Default mode. Uses last Monday through next Sunday.
                  date                    All assignments due between --after and --before.
                  quantity                The N most recent assignments due on or before --before.

                Options:
                  --after <date>          Inclusive start date (date mode)
                  --before <date>         Inclusive end date / cutoff date
                  --white-date <date>     Date that should render as fully white (default: today + 30 days)
                  --quantity <n>          Number of assignments to show (quantity mode)
                  --group-by <mode>       date or class (default: date)
                  --show-grades           Show a grade column
                  --show-progress         Print fetch progress to stderr
                  --hide-submitted        Exclude submitted, graded, and excused work
                  --timezone <zone-id>    Time zone for date filtering and display
                  --base-url <url>        Canvas base URL
                  --help, -h              Show this help message

                Environment / .env:
                  CANVAS_API_TOKEN        Required Canvas access token
                  CANVAS_BASE_URL         Optional Canvas host override
                  CANVAS_TIME_ZONE        Optional default time zone
                """;
    }

    String getBaseUrl()
    {
        return baseUrl;
    }

    String getToken()
    {
        return token;
    }

    ZoneId getZoneId()
    {
        return zoneId;
    }

    Mode getMode()
    {
        return mode;
    }

    GroupBy getGroupBy()
    {
        return groupBy;
    }

    LocalDate getAfterDate()
    {
        return afterDate;
    }

    LocalDate getBeforeDate()
    {
        return beforeDate;
    }

    LocalDate getWhiteDate()
    {
        return whiteDate;
    }

    int getQuantity()
    {
        return quantity;
    }

    boolean isShowGrades()
    {
        return showGrades;
    }

    boolean isShowProgress()
    {
        return showProgress;
    }

    boolean isHideSubmitted()
    {
        return hideSubmitted;
    }

    boolean isHelpRequested()
    {
        return helpRequested;
    }
}
