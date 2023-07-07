package com.android.credentialmanager.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
  small = RoundedCornerShape(100.dp),
  medium = RoundedCornerShape(28.dp),
  large = RoundedCornerShape(0.dp)
)

object EntryShape {
  val TopRoundedCorner = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp)
  val BottomRoundedCorner = RoundedCornerShape(0.dp, 0.dp, 28.dp, 28.dp)
  // Used for middle entries.
  val FullSmallRoundedCorner = RoundedCornerShape(4.dp, 4.dp, 4.dp, 4.dp)
  // Used for when there's a single entry.
  val FullMediumRoundedCorner = RoundedCornerShape(28.dp, 28.dp, 28.dp, 28.dp)
}
