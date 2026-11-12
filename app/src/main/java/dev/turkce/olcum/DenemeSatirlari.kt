package dev.turkce.olcum

/** How long each line stays on the demo clip. */
internal const val DEMO_SECONDS_PER_LINE = 4

/**
 * The English lines burnt into assets/demo/deneme.mp4, in order; arac/demo_klip_uret.py reads them from this file,
 * one quoted line per row. Everyday game talk, short and plain, so the small model translates them well.
 */
internal val DEMO_LINES = listOf(
    "We should leave before it gets dark.",
    "Is anyone there? I heard a noise.",
    "Take this map, you will need it.",
    "The bridge is closed, find another way.",
    "Thank you for helping me today.",
    "Stay close and keep your eyes open.",
    "I left the key under the old table.",
    "Can you carry this box to the car?",
    "The train leaves in ten minutes.",
    "Follow me, I know a safe place.",
    "We will meet again at the harbor.",
    "Good job. Let us rest for a while.",
)
