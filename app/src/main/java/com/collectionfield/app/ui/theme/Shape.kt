package com.collectionfield.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Tightened from the 16dp corners used across the previous screens. This is a
 * work tool used one-handed in the field; 16dp on every surface read as a
 * consumer app and, at this density, softened the alignment the layout depends
 * on. Matches the dashboard's 6/8/12/16px radius scale.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
