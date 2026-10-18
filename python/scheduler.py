#!/usr/bin/env python3
"""
Employee Shift Scheduler - Python implementation
================================================

Collects employee names and ranked shift preferences for each day of the
week, then builds a 7-day schedule that satisfies the company rules:

  * an employee works at most ONE shift per day
  * an employee works at most 5 days per week
  * every shift on every day has at least 2 employees
    (short shifts are topped up with randomly chosen employees who
     have not yet reached 5 days)
  * a shift holds at most MAX_PER_SHIFT employees; when an employee's
    preferred shift is full, the conflict is resolved by trying their
    next-ranked shift, then any other open shift that day, and finally
    the next day

Usage:
    python3 scheduler.py              # interactive menu
    python3 scheduler.py --sample     # run with the built-in sample data
    python3 scheduler.py --sample --seed 7

Requires Python 3.10+ (uses the `match` statement).
"""

import argparse
import random
import sys

# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------
DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday",
        "Friday", "Saturday", "Sunday"]
SHIFTS = ["Morning", "Afternoon", "Evening"]

MIN_PER_SHIFT = 2        # company requirement
MAX_PER_SHIFT = 3        # capacity of one shift (makes "full" possible)
MAX_DAYS_PER_WEEK = 5    # per-employee limit

# Sample data: one entry per day (Mon..Sun).
# "M>A" means Morning is 1st choice, Afternoon 2nd. "-" means no preference.
SAMPLE_DATA = {
    "Alice":  ["M>A",   "M>A", "M",   "-",   "M",   "-",   "-"],
    "Bob":    ["M>E",   "-",   "A>M", "-",   "E",   "E",   "-"],
    "Carmen": ["M",     "-",   "E>A", "E",   "-",   "M",   "-"],
    "David":  ["M>A>E", "E",   "-",   "A",   "A",   "A>M", "A"],
    "Emma":   ["A",     "A>M", "M>A", "-",   "M>A", "-",   "M"],
    "Farid":  ["E",     "-",   "E",   "E>M", "-",   "E",   "E"],
    "Grace":  ["A>M",   "M",   "-",   "M",   "A>E", "-",   "-"],
    "Hiro":   ["E>A",   "-",   "A",   "E>A", "E",   "-",   "-"],
    "Isabel": ["A",     "M>E", "-",   "-",   "-",   "A",   "-"],
    "Jamal":  ["E>M",   "A",   "E>A", "M>E", "-",   "A>E", "-"],
    "Kofi":   ["-",     "E",   "M>A", "A",   "M>E", "-",   "-"],
    "Lena":   ["-",     "-",   "-",   "E>A", "-",   "A",   "E"],
}


# --------------------------------------------------------------------------
# Input parsing
# --------------------------------------------------------------------------
def parse_preference(text):
    """Turn 'M>E' (or 'm,e' / 'Morning Evening') into ['Morning', 'Evening'].

    An empty string or '-' means the employee has no preference that day.
    Raises ValueError on unknown or duplicate shifts.
    """
    cleaned = text.strip().upper()
    if cleaned in ("", "-"):
        return []

    ranking = []
    for token in cleaned.replace(",", ">").replace(" ", ">").split(">"):
        if not token:
            continue
        match token:
            case "M" | "MORNING":
                shift = "Morning"
            case "A" | "AFTERNOON":
                shift = "Afternoon"
            case "E" | "EVENING":
                shift = "Evening"
            case _:
                raise ValueError(f"unknown shift '{token}'")
        if shift in ranking:
            raise ValueError(f"'{shift}' listed twice")
        ranking.append(shift)
    return ranking


def load_sample():
    """Convert SAMPLE_DATA into {name: {day: [ranked shifts]}}."""
    return {name: {day: parse_preference(code) for day, code in zip(DAYS, codes)}
            for name, codes in SAMPLE_DATA.items()}


def collect_from_console():
    """Interactively read employees and their ranked preferences."""
    employees = {}
    print("\nEnter shift preferences as codes ranked best-first:")
    print("  M = Morning, A = Afternoon, E = Evening   e.g.  M>E  or  a,m")
    print("  Leave blank (or '-') for no preference that day.\n")

    while True:
        name = input("Employee name (blank to finish): ").strip()
        if name == "":
            if len(employees) >= 2:
                break
            print("  Please enter at least 2 employees.")
            continue
        if name in employees:
            print("  That name is already entered.")
            continue

        prefs = {}
        for day in DAYS:
            while True:                       # re-prompt until input is valid
                raw = input(f"  {day:<10}: ")
                try:
                    prefs[day] = parse_preference(raw)
                    break
                except ValueError as err:
                    print(f"    Invalid input ({err}). Try again.")
        employees[name] = prefs
    return employees


# --------------------------------------------------------------------------
# Scheduling
# --------------------------------------------------------------------------
class Scheduler:
    def __init__(self, employees, rng):
        self.employees = employees                       # name -> day -> ranking
        self.rng = rng
        self.schedule = {d: {s: [] for s in SHIFTS} for d in DAYS}  # day -> shift -> [(name, note)]
        self.assignment = {d: {} for d in DAYS}          # day -> name -> shift
        self.days_worked = {name: 0 for name in employees}
        self.events = []                                 # conflict / decision log

    # ---- small helpers -------------------------------------------------
    def has_room(self, day, shift):
        return len(self.schedule[day][shift]) < MAX_PER_SHIFT

    def can_work(self, name, day):
        return (name not in self.assignment[day]
                and self.days_worked[name] < MAX_DAYS_PER_WEEK)

    def assign(self, name, day, shift, note=""):
        self.schedule[day][shift].append((name, note))
        self.assignment[day][name] = shift
        self.days_worked[name] += 1

    def log(self, day, message):
        self.events.append(f"{day[:3]}: {message}")

    # ---- look-ahead so preferences never make minimum staffing impossible
    def minimums_reachable(self, day_index, name=None, shift=None):
        """Would every remaining shift still reach MIN_PER_SHIFT if `name`
        were assigned to `shift` today?  Simulates the rest of the week by
        always using the employees with the most days left (optimal here)."""
        day = DAYS[day_index]
        remaining = {n: MAX_DAYS_PER_WEEK - w for n, w in self.days_worked.items()}
        busy_today = set(self.assignment[day])
        counts = {s: len(self.schedule[day][s]) for s in SHIFTS}

        if name is not None:
            remaining[name] -= 1
            busy_today.add(name)
            counts[shift] += 1

        need_today = sum(max(0, MIN_PER_SHIFT - c) for c in counts.values())
        if not self._use_most_available(remaining, need_today, busy_today):
            return False
        for _ in range(day_index + 1, len(DAYS)):
            if not self._use_most_available(remaining, MIN_PER_SHIFT * len(SHIFTS), set()):
                return False
        return True

    @staticmethod
    def _use_most_available(remaining, count, exclude):
        pool = sorted((n for n, r in remaining.items() if r > 0 and n not in exclude),
                      key=lambda n: remaining[n], reverse=True)
        if len(pool) < count:
            return False
        for n in pool[:count]:
            remaining[n] -= 1
        return True

    # ---- placing one employee on one day ------------------------------
    def blocked_reason(self, day_index, name, shift):
        """None if `name` may take `shift` today, otherwise a short reason."""
        if not self.has_room(DAYS[day_index], shift):
            return "is full"
        # Only protect coverage that is still achievable; if the week can no
        # longer be fully staffed anyway, preferences are not blocked.
        if (self.minimums_reachable(day_index)
                and not self.minimums_reachable(day_index, name, shift)):
            return "is held for later-day coverage"
        return None

    def try_place(self, name, day_index, ranking, carried_from=None):
        """Try ranked shifts, then any other open shift. Returns True if placed."""
        day = DAYS[day_index]
        ordinals = ["1st", "2nd", "3rd"]
        first_reason = None

        for rank, shift in enumerate(ranking):
            reason = self.blocked_reason(day_index, name, shift)
            if reason is not None:
                if rank == 0:
                    first_reason = reason
                continue
            if carried_from:
                note = f"moved from {carried_from[:3]}"
            elif rank > 0:
                note = f"{ordinals[rank]} choice"
            else:
                note = ""
            self.assign(name, day, shift, note)
            if rank > 0 and not carried_from:
                self.log(day, f"CONFLICT - {name}'s 1st choice ({ranking[0]}) {first_reason} "
                              f"-> assigned {ordinals[rank]} choice {shift}")
            return True

        # Every preferred shift is unavailable: try the remaining shifts today.
        reasons = [f"{s} {self.blocked_reason(day_index, name, s)}" for s in ranking]
        for shift in SHIFTS:
            if shift in ranking:
                continue
            if self.blocked_reason(day_index, name, shift) is None:
                note = f"moved from {carried_from[:3]}" if carried_from else "reassigned"
                self.assign(name, day, shift, note)
                self.log(day, f"CONFLICT - {name}: {'; '.join(reasons)} "
                              f"-> reassigned to {shift}")
                return True
        return False

    # ---- top up short shifts with random employees --------------------
    def fill_minimums(self, day_index):
        day = DAYS[day_index]
        for shift in SHIFTS:
            while len(self.schedule[day][shift]) < MIN_PER_SHIFT:
                eligible = [n for n in self.employees if self.can_work(n, day)]
                if not eligible:
                    self.log(day, f"WARNING - {shift} is short-staffed; nobody is available")
                    break
                # random choice among those with the most days still available,
                # which keeps enough people free for the rest of the week
                most_left = max(MAX_DAYS_PER_WEEK - self.days_worked[n] for n in eligible)
                pool = [n for n in eligible
                        if MAX_DAYS_PER_WEEK - self.days_worked[n] == most_left]
                pick = self.rng.choice(pool)
                self.assign(pick, day, shift, "random")
                self.log(day, f"STAFFING - {shift} had fewer than {MIN_PER_SHIFT}; "
                              f"randomly added {pick}")

    # ---- main loop -----------------------------------------------------
    def build(self):
        if len(self.employees) * MAX_DAYS_PER_WEEK < MIN_PER_SHIFT * len(SHIFTS) * len(DAYS):
            print("WARNING: not enough employees to staff every shift this week.")

        carried = []                              # [(name, ranking, from_day)]
        for d_idx, day in enumerate(DAYS):
            next_carried = []

            # 1) employees bumped from yesterday get first pick today
            for name, old_ranking, from_day in carried:
                if not self.can_work(name, day):
                    self.log(day, f"{name} could not be moved here (limit reached)")
                    continue
                ranking = self.employees[name][day] or old_ranking
                if not self.try_place(name, d_idx, ranking, carried_from=from_day):
                    self.log(day, f"UNRESOLVED - no open shift for {name} (moved from {from_day[:3]})")

            # 2) today's requests; fewest days worked first, ties broken randomly
            requesters = [n for n in self.employees if self.employees[n][day]]
            self.rng.shuffle(requesters)
            requesters.sort(key=lambda n: self.days_worked[n])

            for name in requesters:
                if name in self.assignment[day]:
                    continue                      # already placed via carry-over
                if self.days_worked[name] >= MAX_DAYS_PER_WEEK:
                    self.log(day, f"{name}'s request declined - already scheduled "
                                  f"{MAX_DAYS_PER_WEEK} days")
                    continue
                ranking = self.employees[name][day]
                if self.try_place(name, d_idx, ranking):
                    continue

                all_full = not any(self.has_room(day, s) for s in SHIFTS)
                if all_full and d_idx < len(DAYS) - 1:
                    next_carried.append((name, ranking, day))
                    self.log(day, f"CONFLICT - every shift is full for {name} "
                                  f"-> moved to {DAYS[d_idx + 1]}")
                elif all_full:
                    self.log(day, f"UNRESOLVED - every shift is full for {name} "
                                  f"and there is no next day this week")
                else:
                    self.log(day, f"{name} held back to keep later shifts staffable")

            # 3) make sure every shift has at least MIN_PER_SHIFT people
            self.fill_minimums(d_idx)
            carried = next_carried

    # ---- output ---------------------------------------------------------
    def print_preferences(self):
        print("\nEMPLOYEE PREFERENCES (ranked, M=Morning A=Afternoon E=Evening)")
        print("-" * 72)
        print(f"{'Employee':<10}" + "".join(f"{d[:3]:<8}" for d in DAYS))
        for name, prefs in self.employees.items():
            cells = [">".join(s[0] for s in prefs[d]) or "-" for d in DAYS]
            print(f"{name:<10}" + "".join(f"{c:<8}" for c in cells))

    def print_schedule(self):
        width = 72
        print("\n" + " FINAL WEEKLY SCHEDULE ".center(width, "="))
        for day in DAYS:
            print(f"\n{day.upper()}")
            for shift in SHIFTS:
                people = [f"{n} ({note})" if note else n
                          for n, note in self.schedule[day][shift]]
                print(f"  {shift:<10}| {', '.join(people)}")
        print("\n" + "=" * width)

        print("\nSCHEDULE GRID (M=Morning A=Afternoon E=Evening, - = off)")
        print("-" * 72)
        print(f"{'Employee':<10}" + "".join(f"{d[:3]:<6}" for d in DAYS) + "Days")
        for name in self.employees:
            cells = [self.assignment[d].get(name, "-")[0] for d in DAYS]
            print(f"{name:<10}" + "".join(f"{c:<6}" for c in cells)
                  + str(self.days_worked[name]))

    def print_events(self):
        print("\nCONFLICTS AND DECISIONS")
        print("-" * 72)
        if not self.events:
            print("  (none)")
        for event in self.events:
            print("  " + event)

    def validate(self):
        """Independently re-check every rule against the finished schedule."""
        print("\nRULE CHECK")
        print("-" * 72)
        one_shift = True
        for day in DAYS:
            names = [n for s in SHIFTS for n, _ in self.schedule[day][s]]
            if len(names) != len(set(names)):
                one_shift = False

        counts = {n: 0 for n in self.employees}
        for day in DAYS:
            for s in SHIFTS:
                for n, _ in self.schedule[day][s]:
                    counts[n] += 1
        max_days = all(c <= MAX_DAYS_PER_WEEK for c in counts.values())

        sizes = [len(self.schedule[d][s]) for d in DAYS for s in SHIFTS]
        minimum = all(size >= MIN_PER_SHIFT for size in sizes)
        capacity = all(size <= MAX_PER_SHIFT for size in sizes)

        results = [
            ("No employee works more than one shift per day", one_shift),
            (f"No employee works more than {MAX_DAYS_PER_WEEK} days", max_days),
            (f"Every shift has at least {MIN_PER_SHIFT} employees", minimum),
            (f"No shift exceeds its capacity of {MAX_PER_SHIFT}", capacity),
        ]
        for label, ok in results:
            print(f"  [{'PASS' if ok else 'FAIL'}] {label}")
        return all(ok for _, ok in results)


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="Weekly employee shift scheduler")
    parser.add_argument("--sample", action="store_true", help="use built-in sample data")
    parser.add_argument("--seed", type=int, default=None, help="random seed for repeatable runs")
    args = parser.parse_args()

    print("=" * 72)
    print(" EMPLOYEE SHIFT SCHEDULER (Python) ".center(72, "="))
    print("=" * 72)

    if args.sample:
        employees = load_sample()
    else:
        choice = ""
        while choice not in ("1", "2"):
            choice = input("1) Use sample data   2) Enter employees manually\nChoice: ").strip()
        employees = load_sample() if choice == "1" else collect_from_console()

    scheduler = Scheduler(employees, random.Random(args.seed))
    scheduler.print_preferences()
    scheduler.build()
    scheduler.print_events()
    scheduler.print_schedule()
    ok = scheduler.validate()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
