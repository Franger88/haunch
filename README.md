# Haunch

A portrait game for a Pixel. You are the night mason. Three arches fail from the left haunch or the right. Your thumb braces one haunch at a time. Mortar runs out if you hold too early or too long. Two fallen arches end the run. Nothing in the game is a win screen. Crown is 300 seconds at 0.75 purity.

## Play

The failing side glows. Hold that side and the arch knocks back open. Slide to another arch without lifting. A narrow strip in the center braces nothing. A second finger, or Back, sets the tools down. The stone waits through a phone call.

- **Endless** is a new seed every run.
- **Tonight's arch** is one seed for the local calendar day, so you can retry the same stone.

The first launch is a lesson: four late braces on the middle arch, then ten seconds with two arches. After that it stays out of the way.

Long-press the rank word during a run for the tuning line (time, mortar, purity, rise).

## Build

JDK 17 is expected at `/home/heath/opt/jdk-17`. The SDK path is in `local.properties`.

```bash
cd /home/heath/haunch
./gradlew test
./gradlew installDebug
```

`test` runs the stone sim with no phone attached. A Pixel is what the timing, haptics, and camera-hole insets need.
