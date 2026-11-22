package com.lissy.fibcam

/**
 * The guide and look pickers' contents (name, artwork), in their order, shared by the camera and the
 * Edit-a-copy screen so both always show the same choices.
 */
object Catalog {
    val guides: List<Triple<Guide, String, Int>> = listOf(
        Triple(Guide.OFF, "Off", R.drawable.comp_off), Triple(Guide.THIRDS, "Thirds", R.drawable.comp_thirds),
        Triple(Guide.PHI_GRID, "Grid", R.drawable.comp_grid), Triple(Guide.SPIRAL, "Spiral", R.drawable.comp_spiral),
        Triple(Guide.TRIANGLES, "Triangle", R.drawable.comp_triangles), Triple(Guide.DIAGONALS, "Diagonals", R.drawable.comp_diagonals),
        Triple(Guide.DYNAMIC, "Dynamic", R.drawable.comp_dynamic), Triple(Guide.CENTRE, "Centre", R.drawable.comp_centre),
        Triple(Guide.MARKS, "Crop", R.drawable.comp_crop))

    val looks: List<Triple<ArtMode, String, Int>> = listOf(
        Triple(ArtMode.OFF, "Original", R.drawable.look_original), Triple(ArtMode.HOLGA, "Plastic", R.drawable.look_holga),
        Triple(ArtMode.PINHOLE, "Pinhole", R.drawable.look_pinhole), Triple(ArtMode.TILT_SHIFT, "Tilt shift", R.drawable.look_tilt_shift),
        Triple(ArtMode.GRAINY_FILM, "Grainy film", R.drawable.look_grainy_film), Triple(ArtMode.CROSS_PROCESS, "Cross process", R.drawable.look_cross_process),
        Triple(ArtMode.CYANOTYPE, "Cyanotype", R.drawable.look_cyanotype), Triple(ArtMode.DREAM_LENS, "Dream lens", R.drawable.look_dream_lens),
        Triple(ArtMode.INFRARED, "Infrared", R.drawable.look_infrared), Triple(ArtMode.KINO, "Kino", R.drawable.look_kino),
        Triple(ArtMode.EXPIRED, "Expired", R.drawable.look_expired), Triple(ArtMode.REDSCALE, "Redscale", R.drawable.look_redscale),
        Triple(ArtMode.SOLARISED, "Solarised", R.drawable.look_solarised), Triple(ArtMode.INSTANT, "Instant", R.drawable.look_instant),
        Triple(ArtMode.BLEACH_BYPASS, "Bleach bypass", R.drawable.look_bleach_bypass))

    /** The alternatives a guide offers (the camera's sub-options row): Spiral's corners, Triangle's diagonals. */
    fun variants(g: Guide): List<Pair<String, Int>> = when (g) {
        Guide.SPIRAL -> listOf("Top left" to R.drawable.or_spiral_tl, "Top right" to R.drawable.or_spiral_tr,
            "Bottom left" to R.drawable.or_spiral_bl, "Bottom right" to R.drawable.or_spiral_br)
        Guide.TRIANGLES -> listOf("Diagonal A" to R.drawable.or_tri_a, "Diagonal B" to R.drawable.or_tri_b)
        else -> emptyList()
    }

    /** Tilt Shift's band only follows guides whose geometry suits a line (as in the camera). */
    val tiltGuides = setOf(Guide.THIRDS, Guide.PHI_GRID, Guide.TRIANGLES, Guide.DYNAMIC)
}
