# Accuracy test: Ustad's brain on 9 public car-fault photos (3 Nov 2026)

## Result

| | |
|---|---|
| Photos | 9 real photos with a known fault (labels from the public datasets) |
| Top-1 correct | **9 / 9 (100%)** |
| Top-3 correct | 9 / 9 (100%) |
| Mean reciprocal rank | 1.00 |

| Photo (source) | True cause | Ustad's ranking |
|---|---|---|
| Flat tyre ([Meera0709/Flat-Tire-Detection](https://github.com/Meera0709/Flat-Tire-Detection), `Test/FlatTire-Test.jpg`) | Tire puncture / low pressure | **tire_puncture 76%**, battery12_weak 3%, tire_worn 3% |
| Door dent + scratch ([ReverendBayes/YOLO11m-Car-Damage-Detector](https://github.com/ReverendBayes/YOLO11m-Car-Damage-Detector), MIT, `public/1.png`) | Collision damage | **collision_damage 61%** |
| Shattered glass (same, `2.png`) | Collision damage | **collision_damage 58%** |
| Broken tail lamp (same, `3.png`) | Collision damage | **collision_damage 58%** |
| Fender dent (same, `4.png`) | Collision damage | **collision_damage 58%** |
| Bumper scratch (same, `5.png`) | Collision damage | **collision_damage 58%** |
| Panel scratches (same, `6.png`) | Collision damage | **collision_damage 58%** |
| Rear bumper dent (same, `7.png`) | Collision damage | **collision_damage 61%** |
| Cracked tyre ([pjluvi/Tyre-Quality-Classification](https://github.com/pjluvi/Tyre-Quality-Classification), MIT, `inputImage.jpg`) | Tyre worn / damaged | **tire_worn 63%**, alignment 17% |

## How it was run

1. The photos came from public GitHub datasets, each with its own label. (Google Images and Wikimedia are blocked from the build machine.)
2. **Seeing:** the symptoms in each photo (e.g. `car_tire_flat`, `car_body_damage`) were read by Claude in place of Gemini, because no Gemini key was available on the build machine. They were not read blind: the dataset labels were known.
3. **Reasoning:** the symptoms went into Ustad's real brain (`KnowledgeBase.rank`, `kb/car.json`), the same code the app runs. The phone's learning was off, as in the in-app test.
4. The score was computed by the app's own `BenchmarkResult`.

## What this does and doesn't show

- ✅ On real photos of **visible exterior faults**, once the symptom is seen, the brain ranks the right cause first with a clear margin.
- ⚠️ These are **easy cases**: each photo shows one obvious symptom. No public dataset reachable from the build machine had **engine-bay faults** (battery corrosion, coolant or oil leaks), which are Ustad's main demo.
- ⚠️ This is not yet an end-to-end test. Gemini didn't read the photos here.

## The real test (10 minutes, on the phone)

1. Put 10–30 photos with a known fault on the phone: the 9 above plus your own engine-bay photos (battery terminals, coolant leak, oil leak, warning lights).
2. Hub → Inspect → **Accuracy test** → Add photos → choose the real cause for each → Run.
3. Share the report. That number (Gemini + brain, end to end) goes on the pitch slide.
