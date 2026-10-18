# Employee Shift Scheduler — Python & Java

A small application that collects employee shift preferences for a 7-day week and builds a schedule that respects company staffing rules. The same logic is implemented twice, in **Python** (dynamically typed, scripting) and **Java** (statically typed, object-oriented), to contrast syntax and control flow across paradigms.

## Rules enforced

| Rule | How it's handled |
|---|---|
| One shift per employee per day | An employee already assigned that day is skipped (`assignment[day]` lookup). |
| Max 5 days per week | `days_worked` is checked before every assignment; extra requests are declined and logged. |
| At least 2 employees per shift | After preferences are placed each day, short shifts are topped up with **randomly chosen** employees who haven't reached 5 days. |
| Shift capacity (3) and conflicts | If a preferred shift is full, the employee gets their next-ranked shift, then any other open shift that day, and if the whole day is full they are **moved to the next day** with first pick. |
| **Bonus:** ranked preferences | Each day's preference is a ranked list, e.g. `M>A` = Morning first, Afternoon second. |

A look-ahead check also stops early-week preferences from using up so many employee-days that later shifts can't reach the 2-person minimum. A final **rule check** independently re-verifies every rule and prints PASS/FAIL.

## Run it

**Python** (3.10+)
```bash
cd python
python3 scheduler.py                    # menu: sample data or manual entry
python3 scheduler.py --sample --seed 7  # sample data, repeatable result
```

**Java** (17+)
```bash
cd java
javac ShiftScheduler.java
java ShiftScheduler                     # menu: sample data or manual entry
java ShiftScheduler --sample --seed 3   # sample data, repeatable result
```

Manual entry: type an employee name, then for each day enter ranked codes (`M`, `A`, `E`, e.g. `M>E` or `a,m`), or leave blank for no preference. Leave the name blank to finish.

Omit `--seed` for a different random result each run. Python and Java use different random number generators, so the same seed gives different (but equally valid) schedules in each language.

## Output

The program prints the input preferences, a log of conflicts and decisions, the final schedule by day and shift, an employee-by-day grid, and the rule check. Notes in the schedule explain each placement: `(2nd choice)`, `(reassigned)`, `(moved from Mon)`, `(random)`.

See `screenshots/` for sample runs of both versions.

## Control structures used

| Construct | Python | Java |
|---|---|---|
| Multi-way branching | `match` / `case` with `\|` patterns | `switch` expressions with `->` arms |
| Conditionals | `if` / `elif` / `else` | `if` / `else if` / `else`, ternary `?:` |
| Counted / collection loops | `for ... in enumerate(...)` | indexed `for`, enhanced `for-each` |
| Condition loops | `while True` + `break` (input validation) | `while`, `do ... while` (input validation) |
| Loop control | `continue`, `break` | `continue`, `break` |
| Error handling | `try` / `except ValueError` | `try` / `catch IllegalArgumentException` |
| Data structures | nested `dict` / `list`, tuples | `EnumMap`, `LinkedHashMap`, `List`, `record` types |

## Layout

```
python/scheduler.py         Python implementation
java/ShiftScheduler.java    Java implementation
screenshots/                Sample output from both versions
```
