package com.example.arsample.helpers

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Pose
import kotlin.math.cos
import kotlin.math.sqrt


interface Point {
    val x: Double
    val y: Double
}

object GeoUtilityHelpers {

    const val DEFAULT_CAMERA_ALTITUDE = - 1

    fun calculateDistance(cameraPose: Pose, anchor: Anchor): Float {

        val anchorPose = anchor.pose

        val dx = anchorPose.tx() - cameraPose.tx()
        val dy = anchorPose.ty() - cameraPose.ty()
        val dz = anchorPose.tz() - cameraPose.tz()

        ///Compute the straight-line distance.
        return sqrt((dx * dx + dy * dy + dz * dz))
    }

    fun calculateObjectPosition(geospatialPose: GeospatialPose, cameraPose: Pose, anchor: Anchor ): Point {

        // xyz coordinates -> Or latitude, longitude, and elevation
        val earth = 6378.137
        //radius of the earth in kilometer
        val pi = Math.PI
        val m = 1 / (2 * pi / 360 * earth) / 1000 //1 meter in degree

        val distance = this.calculateDistance(cameraPose, anchor)

        Log.d("arsample::", "arsample:: ditance meters" + distance)
        val newLat = geospatialPose.latitude + (distance * m)
        val newLon = geospatialPose.longitude + (distance * m) / cos(geospatialPose.latitude * (pi / 180));

        Log.d("arsample::", "arsample::newLat:" + newLat)
        Log.d("arsample::", "arsample::newLat:" + newLat)

        return object: Point {
            override val x = newLat
            override val y = newLon
        }
    }

}