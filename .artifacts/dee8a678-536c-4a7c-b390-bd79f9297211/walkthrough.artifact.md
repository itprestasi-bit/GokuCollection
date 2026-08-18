# Soft Minimalist Collector Dashboard

I have redesigned the collector's home dashboard using a **Soft Minimalist Receipt** aesthetic. This approach removes the previous rigidity while maintaining a professional, task-oriented feel.

## Visual Improvements

1.  **Unified Surface**: Moved away from scattered cards to a cohesive document layout. The main "Duty Status" now feels like a focal point rather than just another card.
2.  **Organic Elevation**: Replaced hard borders with subtle, organic shadows and light tonal backgrounds (Soft Gray 50).
3.  **Refined Typography**:
    *   Primary interface uses **Inter/Sans-serif** for readability and "softness".
    *   **Monospace** is reserved exclusively for technical data (IDs, Status Codes), reinforcing the "Verified Record" thesis without making the whole UI feel "geeky".
4.  **The "Soft Stamp" Action**: The Shift control is now a large, tactile surface that transitions color smoothly when active (Indigo 500 when "On Duty").
5.  **Clean Metrics**: Telemetry data (Battery, Signal, Accuracy) is grouped in a dedicated, airy section with minimalist iconography.

## Technical Changes

*   [HomeScreen.kt](file:///Users/langg__/StudioProjects/GokuCollection/app/src/main/java/com/collectionfield/app/ui/screens/HomeScreen.kt): Completely restructured the layout using a `Scaffold` and refined `Surface` components.
*   [Color.kt](file:///Users/langg__/StudioProjects/GokuCollection/app/src/main/java/com/collectionfield/app/ui/theme/Color.kt): Added the **Slate** and **Soft Gray** palettes for a more modern, premium feel.
*   [Type.kt](file:///Users/langg__/StudioProjects/GokuCollection/app/src/main/java/com/collectionfield/app/ui/theme/Type.kt): Updated type scales to provide better hierarchy and breathing room.
*   [Theme.kt](file:///Users/langg__/StudioProjects/GokuCollection/app/src/main/java/com/collectionfield/app/ui/theme/Theme.kt): Connected the new color scheme and typography to the application theme.

## How to Verify
Run the app and observe the Home screen. You should see a much cleaner header, a prominent work-status card that "pops" slightly more than other elements, and an overall layout that feels lighter and easier to scan.
