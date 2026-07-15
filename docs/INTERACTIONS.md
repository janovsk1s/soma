# Interaction contract

The tap and long-press vocabulary every screen must follow. New screens use
these meanings; deviations need a written reason here.

## The vocabulary

| Gesture | Meaning |
| --- | --- |
| Tap | The primary, expected action. Opens, selects, confirms. |
| Long-press | The deliberate sibling of tap: capture variants and secondary options. Never the only way to reach something destructive. |
| Vertical swipe on a paged list | Turns one full page (five slots). |
| System back | Leaves the current screen without saving anything partial. |

## Per surface

| Surface | Tap | Long-press |
| --- | --- | --- |
| Day title (home) | Calendar | Calendar |
| Capture line (home) | Type a text entry | Record a voice note |
| `+` (home) | Type a text entry | Photo capture |
| Entry row | Open the entry | Entry options |
| Important row | Item detail (full text) | Item options |
| Important `○` | Complete the item | — |
| Log row | Log detail | Log options |
| Calendar day | Open that day | — |
| Calendar "search" row | Search | — |
| Settings row | The row's single action | — |
| About version row | — (triple-tap opens Developer) | — |
| Trash item | Item options | Item options |

## First-use hints

Hidden gestures earn one quiet, dim, inline text hint on the home screen —
never a popup, never repeated. Each hint retires forever the first time its
gesture is used, in this order: hold-to-speak, hold-`+`-for-photo,
tap-the-date. State lives in `SomaPrefs.GestureHint`.

## Irreversible actions

Anything that cannot be undone takes a second, clearly-worded tap on the same
row ("tap again to delete forever"), disarmed by leaving the screen. Today the
only irreversible act is purging from the trash.

## Known debts

- Two header implementations still exist (`SimpleTopBar` and the hand-rolled
  Important header with its right-side toggle). Consolidate when either is
  next touched; new screens use `SimpleTopBar`.
