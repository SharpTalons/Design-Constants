# Design Constants

Feature specifications, packaged as Claude Code skills, for building the same recurring app
features the same way every time.

Point another project at this repo and ask for a feature — *"add a What's New changelog"* —
and Claude picks up the specification, the rules behind it and copy-ready code, instead of
reinventing the feature from scratch with slightly different decisions than last time.

## Install it in another project

From the repo you want to build the feature in:

```
/plugin marketplace add SharpTalons/Design-Constants
/plugin install design-constants
```

The six skills then appear in that project permanently, for anyone working in it. They
trigger on their own — you say *"we need release notes in the app"* and the changelog skill
fires without you naming a file.

If you would rather not install a plugin, each skill is an ordinary folder: copy
`plugins/design-constants/skills/<name>/` into that project's `.claude/skills/`.

## What's in it

| Skill | Builds |
|---|---|
| **changelog** | An in-app "What's New" dialog: version history, a once-per-person-per-version popup, a Settings version link, and a build-time check that stops the version number disagreeing with itself. |
| **my-account** | A personal settings sub-screen: change password, light/dark mode and accent, install to home screen, haptics and tap sounds. |
| **user-management** | An administrator-only accounts screen: create, edit, reset password, per-person permissions, remove — with the guard rails that stop anyone locking the organisation out. |
| **update-check** | An in-app update check for apps installed outside a store (sideloaded Android APKs): a check on every open against a public GitHub releases page, an Update / Ignore prompt, a safe download handed to Android's installer, a Settings row, and the CI that publishes each release (to a public releases-only repository when the code is private). |
| **floating-button** | A button that floats over other apps while an Android app keeps working in the background: tap to return, drag to the ✕ to close, a glow on events. Comes with the foreground service and its notification (Stop, Open, an optional toggle) and the Settings switch that asks for "Display over other apps". |
| **design-foundations** | The groundwork the other three share: the light/dark token system with a true-black dark theme, accessible dialog plumbing, and the server-owned permission model. |

## How a skill is laid out

```
skills/changelog/
├── SKILL.md          the map: build order, the rules that get broken, stack adaptation
├── references/       read on demand
│   ├── spec.md               the complete specification
│   ├── writing-entries.md    house style for release entries (stands alone)
│   └── release-discipline.md the version-sync checklist
└── assets/           copy-ready implementation files
```

`SKILL.md` is short on purpose. It is always in context once the skill triggers, so it
carries the decisions and points at the rest. The long specification only loads when
somebody is actually building the thing — and `writing-entries.md` can be read on its own
months later, when the job is composing one release note rather than building the feature.

## About the stack

The reference code is **React + Vite**, except **update-check** and **floating-button**,
whose reference code is **Android (Kotlin and Jetpack Compose)** (update-check with a
GitHub Actions workflow), because that is where an app updates itself and draws over other
apps. The rules in each `SKILL.md` are written to be
stack-neutral, and for the web skills the three things that are genuinely Vite- or React-specific
(`import.meta.env.DEV`, direct `localStorage` access, JSX and hooks) are called out with
their equivalents for Next.js, Vue and React Native.

So the skills are useful in a Next.js or Vue project — the agent adapts knowingly instead of
pasting Vite syntax into something that is not Vite. Anything specified exactly, like the
neutral colour scale in `design-foundations`, says so.

## Keeping projects up to date

Each skill has a version, listed with what its newest version changed in
[`plugins/design-constants/skill-versions.txt`](plugins/design-constants/skill-versions.txt).

A project records which skills it was built from, and at which version, in a
`.design-constants` file at its root (the skills write it when they build a feature):

```
update-check 1.1.0
floating-button 1.0.0
```

At the start of every Claude Code session in that project, a check compares those lines
with the newest versions and, when one is behind, tells the person what changed and offers
to bring the feature up to date. It prints nothing when everything is current, and stays
quiet when GitHub can't be reached (it then uses the list that came with the plugin).

- **With the plugin installed**, the check is part of it (`hooks/hooks.json`); nothing
  else to set up.
- **With skills copied by hand**, copy `plugins/design-constants/hooks/check-skill-versions.sh`
  into the project's `.claude/hooks/` and add it to `.claude/settings.json`:

  ```json
  { "hooks": { "SessionStart": [ { "hooks": [
      { "type": "command", "command": "bash \"$CLAUDE_PROJECT_DIR/.claude/hooks/check-skill-versions.sh\"" }
  ] } ] } }
  ```

Nothing is pushed into other repositories: a project hears about a new version the next
time someone opens a session in it.

## Adding a feature to this repo

1. Create `plugins/design-constants/skills/<name>/` with a `SKILL.md`.
2. Write the `description` for triggering: say what it builds *and* the phrases someone
   would actually use, including the ones that never name the feature. That field is the
   only thing deciding whether the skill fires.
3. Put the long specification in `references/spec.md`, with a table of contents.
4. Put copy-ready code in `assets/`, commented with *why*, not just *what*.
5. Keep `SKILL.md` under about 500 lines — it is the map, not the territory.
6. Add the skill to `skill-versions.txt` at `1.0.0`, and end its `SKILL.md` with the
   "Record what you built" section the other skills have.

## Changing a feature

Raise the skill's version in `skill-versions.txt` in the same commit, and replace its
sentence there with what changed: PATCH for wording or a fix, MINOR for new behaviour,
MAJOR when apps built from it must change how they use it. Also raise the plugin's own
version in `.claude-plugin/marketplace.json` and `plugins/design-constants/.claude-plugin/plugin.json`.
Every project built from that skill is then told at its next session.
