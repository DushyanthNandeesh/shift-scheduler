import java.util.*;

/**
 * Employee Shift Scheduler - Java implementation
 * ==============================================
 *
 * Collects employee names and ranked shift preferences for each day of the
 * week, then builds a 7-day schedule that satisfies the company rules:
 *
 *   - an employee works at most ONE shift per day
 *   - an employee works at most 5 days per week
 *   - every shift on every day has at least 2 employees (short shifts are
 *     topped up with randomly chosen employees who have not reached 5 days)
 *   - a shift holds at most MAX_PER_SHIFT employees; when an employee's
 *     preferred shift is full, the conflict is resolved by trying their
 *     next-ranked shift, then any other open shift that day, then the next day
 *
 * Compile:  javac ShiftScheduler.java
 * Run:      java ShiftScheduler                 (interactive menu)
 *           java ShiftScheduler --sample        (built-in sample data)
 *           java ShiftScheduler --sample --seed 7
 *
 * Requires Java 17+ (uses records and switch expressions).
 */
public class ShiftScheduler {

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------
    enum Day { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;
        String label() { return name().charAt(0) + name().substring(1).toLowerCase(); }
        String abbr()  { return label().substring(0, 3); }
    }

    enum Shift { MORNING, AFTERNOON, EVENING;
        String label() { return name().charAt(0) + name().substring(1).toLowerCase(); }
        char code()    { return name().charAt(0); }
    }

    static final int MIN_PER_SHIFT = 2;       // company requirement
    static final int MAX_PER_SHIFT = 3;       // capacity of one shift
    static final int MAX_DAYS_PER_WEEK = 5;   // per-employee limit

    static final Day[] DAYS = Day.values();
    static final Shift[] SHIFTS = Shift.values();

    /** Sample data, one code per day Mon..Sun. "M>A" = Morning 1st, Afternoon 2nd; "-" = none. */
    static final String[][] SAMPLE_DATA = {
        {"Alice",  "M>A",   "M>A", "M",   "-",   "M",   "-",   "-"},
        {"Bob",    "M>E",   "-",   "A>M", "-",   "E",   "E",   "-"},
        {"Carmen", "M",     "-",   "E>A", "E",   "-",   "M",   "-"},
        {"David",  "M>A>E", "E",   "-",   "A",   "A",   "A>M", "A"},
        {"Emma",   "A",     "A>M", "M>A", "-",   "M>A", "-",   "M"},
        {"Farid",  "E",     "-",   "E",   "E>M", "-",   "E",   "E"},
        {"Grace",  "A>M",   "M",   "-",   "M",   "A>E", "-",   "-"},
        {"Hiro",   "E>A",   "-",   "A",   "E>A", "E",   "-",   "-"},
        {"Isabel", "A",     "M>E", "-",   "-",   "-",   "A",   "-"},
        {"Jamal",  "E>M",   "A",   "E>A", "M>E", "-",   "A>E", "-"},
        {"Kofi",   "-",     "E",   "M>A", "A",   "M>E", "-",   "-"},
        {"Lena",   "-",     "-",   "-",   "E>A", "-",   "A",   "E"},
    };

    /** One person placed on one shift, plus a note on how they got there. */
    record Slot(String name, String note) {
        @Override public String toString() { return note.isEmpty() ? name : name + " (" + note + ")"; }
    }

    /** An employee bumped from a full day and carried to the next one. */
    record Carried(String name, List<Shift> ranking, Day fromDay) {}

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private final Map<String, Map<Day, List<Shift>>> employees;           // name -> day -> ranking
    private final Map<Day, Map<Shift, List<Slot>>> schedule = new EnumMap<>(Day.class);
    private final Map<Day, Map<String, Shift>> assignment = new EnumMap<>(Day.class);
    private final Map<String, Integer> daysWorked = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private final Random rng;

    ShiftScheduler(Map<String, Map<Day, List<Shift>>> employees, Random rng) {
        this.employees = employees;
        this.rng = rng;
        for (Day d : DAYS) {
            Map<Shift, List<Slot>> byShift = new EnumMap<>(Shift.class);
            for (Shift s : SHIFTS) byShift.put(s, new ArrayList<>());
            schedule.put(d, byShift);
            assignment.put(d, new HashMap<>());
        }
        for (String name : employees.keySet()) daysWorked.put(name, 0);
    }

    // ------------------------------------------------------------------
    // Input parsing
    // ------------------------------------------------------------------
    /** "M>E", "m,e" or "Morning Evening" -> [MORNING, EVENING]; "" or "-" -> []. */
    static List<Shift> parsePreference(String text) {
        String cleaned = text.trim().toUpperCase();
        List<Shift> ranking = new ArrayList<>();
        if (cleaned.isEmpty() || cleaned.equals("-")) return ranking;

        for (String token : cleaned.replace(",", ">").replace(" ", ">").split(">")) {
            if (token.isEmpty()) continue;
            Shift shift = switch (token) {
                case "M", "MORNING"   -> Shift.MORNING;
                case "A", "AFTERNOON" -> Shift.AFTERNOON;
                case "E", "EVENING"   -> Shift.EVENING;
                default -> throw new IllegalArgumentException("unknown shift '" + token + "'");
            };
            if (ranking.contains(shift))
                throw new IllegalArgumentException("'" + shift.label() + "' listed twice");
            ranking.add(shift);
        }
        return ranking;
    }

    static Map<String, Map<Day, List<Shift>>> loadSample() {
        Map<String, Map<Day, List<Shift>>> result = new LinkedHashMap<>();
        for (String[] row : SAMPLE_DATA) {
            Map<Day, List<Shift>> prefs = new EnumMap<>(Day.class);
            for (int i = 0; i < DAYS.length; i++) prefs.put(DAYS[i], parsePreference(row[i + 1]));
            result.put(row[0], prefs);
        }
        return result;
    }

    static Map<String, Map<Day, List<Shift>>> collectFromConsole(Scanner in) {
        Map<String, Map<Day, List<Shift>>> result = new LinkedHashMap<>();
        System.out.println("\nEnter shift preferences as codes ranked best-first:");
        System.out.println("  M = Morning, A = Afternoon, E = Evening   e.g.  M>E  or  a,m");
        System.out.println("  Leave blank (or '-') for no preference that day.\n");

        while (true) {
            System.out.print("Employee name (blank to finish): ");
            if (!in.hasNextLine()) break;
            String name = in.nextLine().trim();
            if (name.isEmpty()) {
                if (result.size() >= 2) break;
                System.out.println("  Please enter at least 2 employees.");
                continue;
            }
            if (result.containsKey(name)) {
                System.out.println("  That name is already entered.");
                continue;
            }
            Map<Day, List<Shift>> prefs = new EnumMap<>(Day.class);
            for (Day day : DAYS) {
                boolean valid = false;
                do {                                  // re-prompt until input is valid
                    System.out.printf("  %-10s: ", day.label());
                    String raw = in.hasNextLine() ? in.nextLine() : "";
                    try {
                        prefs.put(day, parsePreference(raw));
                        valid = true;
                    } catch (IllegalArgumentException e) {
                        System.out.println("    Invalid input (" + e.getMessage() + "). Try again.");
                    }
                } while (!valid);
            }
            result.put(name, prefs);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------
    private boolean hasRoom(Day day, Shift shift) {
        return schedule.get(day).get(shift).size() < MAX_PER_SHIFT;
    }

    private boolean canWork(String name, Day day) {
        return !assignment.get(day).containsKey(name) && daysWorked.get(name) < MAX_DAYS_PER_WEEK;
    }

    private void assign(String name, Day day, Shift shift, String note) {
        schedule.get(day).get(shift).add(new Slot(name, note));
        assignment.get(day).put(name, shift);
        daysWorked.merge(name, 1, Integer::sum);
    }

    private void log(Day day, String message) {
        events.add(day.abbr() + ": " + message);
    }

    // ------------------------------------------------------------------
    // Look-ahead so preferences never make minimum staffing impossible
    // ------------------------------------------------------------------
    /**
     * Would every remaining shift still reach MIN_PER_SHIFT if {@code name}
     * were assigned to {@code shift} today? Simulates the rest of the week by
     * always using the employees with the most days left.
     */
    private boolean minimumsReachable(int dayIndex, String name, Shift shift) {
        Day day = DAYS[dayIndex];
        Map<String, Integer> remaining = new HashMap<>();
        daysWorked.forEach((n, w) -> remaining.put(n, MAX_DAYS_PER_WEEK - w));
        Set<String> busyToday = new HashSet<>(assignment.get(day).keySet());
        Map<Shift, Integer> counts = new EnumMap<>(Shift.class);
        for (Shift s : SHIFTS) counts.put(s, schedule.get(day).get(s).size());

        if (name != null) {
            remaining.merge(name, -1, Integer::sum);
            busyToday.add(name);
            counts.merge(shift, 1, Integer::sum);
        }

        int needToday = 0;
        for (int c : counts.values()) needToday += Math.max(0, MIN_PER_SHIFT - c);
        if (!useMostAvailable(remaining, needToday, busyToday)) return false;

        for (int d = dayIndex + 1; d < DAYS.length; d++) {
            if (!useMostAvailable(remaining, MIN_PER_SHIFT * SHIFTS.length, Set.of())) return false;
        }
        return true;
    }

    private static boolean useMostAvailable(Map<String, Integer> remaining, int count, Set<String> exclude) {
        List<String> pool = new ArrayList<>();
        for (var e : remaining.entrySet()) {
            if (e.getValue() > 0 && !exclude.contains(e.getKey())) pool.add(e.getKey());
        }
        if (pool.size() < count) return false;
        pool.sort((a, b) -> remaining.get(b) - remaining.get(a));
        for (int i = 0; i < count; i++) remaining.merge(pool.get(i), -1, Integer::sum);
        return true;
    }

    /** null if {@code name} may take {@code shift} today, otherwise a short reason. */
    private String blockedReason(int dayIndex, String name, Shift shift) {
        if (!hasRoom(DAYS[dayIndex], shift)) return "is full";
        // Only protect coverage that is still achievable; if the week can no
        // longer be fully staffed anyway, preferences are not blocked.
        if (minimumsReachable(dayIndex, null, null) && !minimumsReachable(dayIndex, name, shift))
            return "is held for later-day coverage";
        return null;
    }

    // ------------------------------------------------------------------
    // Placing one employee on one day
    // ------------------------------------------------------------------
    private boolean tryPlace(String name, int dayIndex, List<Shift> ranking, Day carriedFrom) {
        Day day = DAYS[dayIndex];
        String[] ordinals = {"1st", "2nd", "3rd"};
        String firstReason = null;

        for (int rank = 0; rank < ranking.size(); rank++) {
            Shift shift = ranking.get(rank);
            String reason = blockedReason(dayIndex, name, shift);
            if (reason != null) {
                if (rank == 0) firstReason = reason;
                continue;
            }
            String note;
            if (carriedFrom != null)  note = "moved from " + carriedFrom.abbr();
            else if (rank > 0)        note = ordinals[rank] + " choice";
            else                      note = "";
            assign(name, day, shift, note);
            if (rank > 0 && carriedFrom == null) {
                log(day, "CONFLICT - " + name + "'s 1st choice (" + ranking.get(0).label() + ") "
                        + firstReason + " -> assigned " + ordinals[rank] + " choice " + shift.label());
            }
            return true;
        }

        // Every preferred shift is unavailable: try the remaining shifts today.
        List<String> reasons = new ArrayList<>();
        for (Shift s : ranking) reasons.add(s.label() + " " + blockedReason(dayIndex, name, s));

        for (Shift shift : SHIFTS) {
            if (ranking.contains(shift)) continue;
            if (blockedReason(dayIndex, name, shift) == null) {
                String note = carriedFrom != null ? "moved from " + carriedFrom.abbr() : "reassigned";
                assign(name, day, shift, note);
                log(day, "CONFLICT - " + name + ": " + String.join("; ", reasons)
                        + " -> reassigned to " + shift.label());
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Top up short shifts with random employees
    // ------------------------------------------------------------------
    private void fillMinimums(int dayIndex) {
        Day day = DAYS[dayIndex];
        for (Shift shift : SHIFTS) {
            while (schedule.get(day).get(shift).size() < MIN_PER_SHIFT) {
                List<String> eligible = new ArrayList<>();
                for (String n : employees.keySet()) if (canWork(n, day)) eligible.add(n);
                if (eligible.isEmpty()) {
                    log(day, "WARNING - " + shift.label() + " is short-staffed; nobody is available");
                    break;
                }
                // random choice among those with the most days still available
                int mostLeft = 0;
                for (String n : eligible) mostLeft = Math.max(mostLeft, MAX_DAYS_PER_WEEK - daysWorked.get(n));
                List<String> pool = new ArrayList<>();
                for (String n : eligible) if (MAX_DAYS_PER_WEEK - daysWorked.get(n) == mostLeft) pool.add(n);

                String pick = pool.get(rng.nextInt(pool.size()));
                assign(pick, day, shift, "random");
                log(day, "STAFFING - " + shift.label() + " had fewer than " + MIN_PER_SHIFT
                        + "; randomly added " + pick);
            }
        }
    }

    // ------------------------------------------------------------------
    // Main scheduling loop
    // ------------------------------------------------------------------
    void build() {
        if (employees.size() * MAX_DAYS_PER_WEEK < MIN_PER_SHIFT * SHIFTS.length * DAYS.length) {
            System.out.println("WARNING: not enough employees to staff every shift this week.");
        }

        List<Carried> carried = new ArrayList<>();
        for (int dIdx = 0; dIdx < DAYS.length; dIdx++) {
            Day day = DAYS[dIdx];
            List<Carried> nextCarried = new ArrayList<>();

            // 1) employees bumped from yesterday get first pick today
            for (Carried c : carried) {
                if (!canWork(c.name(), day)) {
                    log(day, c.name() + " could not be moved here (limit reached)");
                    continue;
                }
                List<Shift> own = employees.get(c.name()).get(day);
                List<Shift> ranking = own.isEmpty() ? c.ranking() : own;
                if (!tryPlace(c.name(), dIdx, ranking, c.fromDay())) {
                    log(day, "UNRESOLVED - no open shift for " + c.name()
                            + " (moved from " + c.fromDay().abbr() + ")");
                }
            }

            // 2) today's requests; fewest days worked first, ties broken randomly
            List<String> requesters = new ArrayList<>();
            for (String n : employees.keySet()) if (!employees.get(n).get(day).isEmpty()) requesters.add(n);
            Collections.shuffle(requesters, rng);
            requesters.sort(Comparator.comparingInt(daysWorked::get));   // stable sort

            for (String name : requesters) {
                if (assignment.get(day).containsKey(name)) continue;     // placed via carry-over
                if (daysWorked.get(name) >= MAX_DAYS_PER_WEEK) {
                    log(day, name + "'s request declined - already scheduled " + MAX_DAYS_PER_WEEK + " days");
                    continue;
                }
                List<Shift> ranking = employees.get(name).get(day);
                if (tryPlace(name, dIdx, ranking, null)) continue;

                boolean allFull = true;
                for (Shift s : SHIFTS) if (hasRoom(day, s)) { allFull = false; break; }

                if (allFull && dIdx < DAYS.length - 1) {
                    nextCarried.add(new Carried(name, ranking, day));
                    log(day, "CONFLICT - every shift is full for " + name
                            + " -> moved to " + DAYS[dIdx + 1].label());
                } else if (allFull) {
                    log(day, "UNRESOLVED - every shift is full for " + name
                            + " and there is no next day this week");
                } else {
                    log(day, name + " held back to keep later shifts staffable");
                }
            }

            // 3) make sure every shift has at least MIN_PER_SHIFT people
            fillMinimums(dIdx);
            carried = nextCarried;
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------
    void printPreferences() {
        System.out.println("\nEMPLOYEE PREFERENCES (ranked, M=Morning A=Afternoon E=Evening)");
        System.out.println("-".repeat(72));
        StringBuilder header = new StringBuilder(String.format("%-10s", "Employee"));
        for (Day d : DAYS) header.append(String.format("%-8s", d.abbr()));
        System.out.println(header);
        for (var entry : employees.entrySet()) {
            StringBuilder row = new StringBuilder(String.format("%-10s", entry.getKey()));
            for (Day d : DAYS) {
                List<Shift> r = entry.getValue().get(d);
                StringBuilder cell = new StringBuilder();
                for (int i = 0; i < r.size(); i++) {
                    if (i > 0) cell.append('>');
                    cell.append(r.get(i).code());
                }
                row.append(String.format("%-8s", cell.length() == 0 ? "-" : cell));
            }
            System.out.println(row);
        }
    }

    void printEvents() {
        System.out.println("\nCONFLICTS AND DECISIONS");
        System.out.println("-".repeat(72));
        if (events.isEmpty()) System.out.println("  (none)");
        for (String e : events) System.out.println("  " + e);
    }

    void printSchedule() {
        int width = 72;
        String title = " FINAL WEEKLY SCHEDULE ";
        int left = (width - title.length()) / 2;
        System.out.println("\n" + "=".repeat(left) + title + "=".repeat(width - left - title.length()));
        for (Day day : DAYS) {
            System.out.println("\n" + day.name());
            for (Shift shift : SHIFTS) {
                List<String> people = new ArrayList<>();
                for (Slot slot : schedule.get(day).get(shift)) people.add(slot.toString());
                System.out.printf("  %-10s| %s%n", shift.label(), String.join(", ", people));
            }
        }
        System.out.println("\n" + "=".repeat(width));

        System.out.println("\nSCHEDULE GRID (M=Morning A=Afternoon E=Evening, - = off)");
        System.out.println("-".repeat(72));
        StringBuilder header = new StringBuilder(String.format("%-10s", "Employee"));
        for (Day d : DAYS) header.append(String.format("%-6s", d.abbr()));
        System.out.println(header.append("Days"));
        for (String name : employees.keySet()) {
            StringBuilder row = new StringBuilder(String.format("%-10s", name));
            for (Day d : DAYS) {
                Shift s = assignment.get(d).get(name);
                row.append(String.format("%-6s", s == null ? "-" : String.valueOf(s.code())));
            }
            System.out.println(row.append(daysWorked.get(name)));
        }
    }

    /** Independently re-check every rule against the finished schedule. */
    boolean validate() {
        System.out.println("\nRULE CHECK");
        System.out.println("-".repeat(72));

        boolean oneShift = true;
        Map<String, Integer> counts = new HashMap<>();
        boolean minimum = true, capacity = true;

        for (Day day : DAYS) {
            Set<String> seen = new HashSet<>();
            for (Shift s : SHIFTS) {
                List<Slot> slots = schedule.get(day).get(s);
                if (slots.size() < MIN_PER_SHIFT) minimum = false;
                if (slots.size() > MAX_PER_SHIFT) capacity = false;
                for (Slot slot : slots) {
                    if (!seen.add(slot.name())) oneShift = false;
                    counts.merge(slot.name(), 1, Integer::sum);
                }
            }
        }
        boolean maxDays = counts.values().stream().allMatch(c -> c <= MAX_DAYS_PER_WEEK);

        String[] labels = {
            "No employee works more than one shift per day",
            "No employee works more than " + MAX_DAYS_PER_WEEK + " days",
            "Every shift has at least " + MIN_PER_SHIFT + " employees",
            "No shift exceeds its capacity of " + MAX_PER_SHIFT,
        };
        boolean[] results = {oneShift, maxDays, minimum, capacity};
        boolean all = true;
        for (int i = 0; i < labels.length; i++) {
            System.out.println("  [" + (results[i] ? "PASS" : "FAIL") + "] " + labels[i]);
            all &= results[i];
        }
        return all;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        boolean sample = false;
        Long seed = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sample" -> sample = true;
                case "--seed" -> {
                    if (i + 1 < args.length) seed = Long.parseLong(args[++i]);
                }
                default -> System.out.println("Ignoring unknown argument: " + args[i]);
            }
        }

        System.out.println("=".repeat(72));
        String title = " EMPLOYEE SHIFT SCHEDULER (Java) ";
        int left = (72 - title.length()) / 2;
        System.out.println("=".repeat(left) + title + "=".repeat(72 - left - title.length()));
        System.out.println("=".repeat(72));

        Map<String, Map<Day, List<Shift>>> employees;
        if (sample) {
            employees = loadSample();
        } else {
            Scanner in = new Scanner(System.in);
            String choice = "";
            while (!choice.equals("1") && !choice.equals("2")) {
                System.out.print("1) Use sample data   2) Enter employees manually\nChoice: ");
                if (!in.hasNextLine()) return;
                choice = in.nextLine().trim();
            }
            employees = choice.equals("1") ? loadSample() : collectFromConsole(in);
        }

        ShiftScheduler scheduler = new ShiftScheduler(employees, seed == null ? new Random() : new Random(seed));
        scheduler.printPreferences();
        scheduler.build();
        scheduler.printEvents();
        scheduler.printSchedule();
        System.exit(scheduler.validate() ? 0 : 1);
    }
}
