# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Italic, strikethrough and underline in a label's markup: a span's
  `:style "italic"` or `"oblique"`, `:strikethrough "true"`, and `:underline`
  other than `"none"`.
- The backend names once each markup element that styles nothing on Android,
  such as `[:tt …]`, and still draws its text.

### Changed

- `ga_text_style` takes a Typeface style (1 bold, 2 italic, 3 both) where it
  took bold, and `ga_run` takes a Typeface style, an underline and a
  strikethrough. Both halves move together under one pin.

### Fixed

- Pango's `[:b …]`, `[:i …]`, `[:s …]` and `[:u …]` drew their text plain on
  Android. They now draw bold, italic, struck through and underlined, as on
  glimmer-uikit (#3).

## [0.1.0] - 2026-10-10

### Added

- The Android backend for glimmer, in its first release. Requiring
  `glimmer-android.core` installs it, and an app's entry export calls
  `glimmer-android.core/mount!`.
- The views: `:vbox`, `:hbox`, `:layers` and `:scroll`; `:label`, `:button`,
  `:checkbutton`, `:image` and `:gradient`.
- The props: layout (`:width`, `:height`, `:halign`, `:valign`, `:hexpand`,
  `:vexpand`, `:vfill`, `:full-bleed`, `:safe`), colours, borders and radii,
  Pango markup as hiccup data, dates, and a button's or checkbutton's `:height`
  as its least height. An unknown prop is named once and ignored.
- The widget layer: prop groups that reset a prop a later render leaves out,
  the handler of the latest render behind each listener, and a whole subtree
  released on removal.
- The app loop: Back (`back!`, `take-back!`), `on-background!`, `files-dir`,
  `version` and `open-url!`, on the Java Activity and C half under `android/`.
- `glimmer-android.fake`, a fake of the C half for testing an app's screens
  without a device.
- `scripts/emulator-lib`, the emulator helpers an app's proof sources; its
  contract is in the README.

### Fixed

- ClipCheck logged one clip once per layout pass, and logged states that
  lasted a single pass. It now walks the tree once layout has been quiet for
  250 ms.
- `emulator-lib`'s cleanup lost settings written just before the kill, such as
  the turn back to portrait, so the next boot could start turned. It now waits
  and syncs before it kills an emulator it booted.

[Unreleased]: https://github.com/jolt-lang/glimmer-android/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/jolt-lang/glimmer-android/releases/tag/v0.1.0
