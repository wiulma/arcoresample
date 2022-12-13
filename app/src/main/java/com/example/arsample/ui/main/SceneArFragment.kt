/*
 * Copyright 2019 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.arsample.ui.main

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.arsample.R
import com.example.arsample.data.AnchorData
import com.example.arsample.helpers.*
import com.example.arsample.rendering.BackgroundRenderer
import com.example.arsample.rendering.ObjectRenderer
import com.example.arsample.rendering.PlaneRenderer
import com.example.arsample.rendering.PointCloudRenderer
import com.google.ar.core.*
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Point
import com.google.ar.core.exceptions.*
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Main Fragment for the Cloud Anchors Codelab.
 *
 * This is where the AR Session and the Cloud Anchors are managed.
 */
class SceneArFragment() : Fragment(), GLSurfaceView.Renderer {
    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private lateinit var rootView: View
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var camera: Camera
    private var installRequested = false
    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    private val messageSnackbarHelper: SnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private var trackingStateHelper: TrackingStateHelper? = null
    private var tapHelper: TapHelper? = null
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val virtualObject: ObjectRenderer = ObjectRenderer()
    private val virtualObjectShadow: ObjectRenderer = ObjectRenderer()
    private val planeRenderer: PlaneRenderer = PlaneRenderer()
    private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()
    private val cloudAnchorManager: CloudAnchorManager = CloudAnchorManager()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)
    private val andyColor = floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)
    private var currentAnchor: Anchor? = null
    private var earthAnchor: Anchor? = null
    private var currentAnchorData: AnchorData? = null
    private lateinit var resolveButton: Button
    private var currentAnchorListIndex = -1

    override fun onAttach(context: Context) {
        super.onAttach(context)
        tapHelper = TapHelper(context)
        trackingStateHelper = TrackingStateHelper(requireActivity())
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        if (container == null) {
            return null
        }
        rootView = inflater.inflate(R.layout.cloud_anchor_fragment, container, false)
        val clearButton = rootView.findViewById<Button>(R.id.clear_button)
        clearButton.setOnClickListener { _: View? -> onClearButtonPressed() }

        val saveButton = rootView.findViewById<Button>(R.id.save_button)
        saveButton.setOnClickListener { _ -> onSaveButtonPressed() }

        resolveButton = rootView.findViewById<Button>(R.id.resolve_button)
        resolveButton.setOnClickListener { _ -> onResolveButtonPressed() }

        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        surfaceView = rootView.findViewById<GLSurfaceView>(R.id.surfaceView)

        displayRotationHelper = DisplayRotationHelper(requireContext())
        surfaceView.setOnTouchListener(tapHelper)
        surfaceView.setPreserveEGLContextOnPause(true)
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)

        // Setup ARCore session lifecycle helper and configuration.
        arCoreSessionHelper = ARCoreSessionLifecycleHelper(requireActivity())
        // If Session creation or Session.resume() fails, display a message and log detailed
        // information.
        arCoreSessionHelper.exceptionCallback = { exception ->
            val message =
                    when (exception) {
                        is UnavailableUserDeclinedInstallationException ->
                                requireContext().getString(R.string.install_gp_services)
                        is UnavailableApkTooOldException -> requireContext().getString(R.string.update_arcore)
                        is UnavailableSdkTooOldException -> requireContext().getString(R.string.update_app)
                        is UnavailableDeviceNotCompatibleException -> requireContext().getString(R.string.ar_not_supported)
                        is CameraNotAvailableException ->
                                    requireContext().getString(R.string.camera_not_available)
                        else -> requireContext().getString(R.string.failed_create_ar_session)
                        //TODO: send exception to sentry
                    }
            Log.e(TAG, "ARCore threw an exception", exception)
            messageSnackbarHelper.showError(requireActivity(), message)
        }

        // Configure session features.
        arCoreSessionHelper.beforeSessionResume = ::configureSession
        lifecycle.addObserver(arCoreSessionHelper)
    }

    override fun onResume() {
        super.onResume()
        arCoreSessionHelper.onResume(this)
        surfaceView.onResume()
        displayRotationHelper?.onResume()
    }


    private fun configureSession(session: Session) {
        val config = session.config
        config.geospatialMode = Config.GeospatialMode.ENABLED
        config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL

        val isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        if (isDepthSupported) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC)
        } else {
            config.setDepthMode(Config.DepthMode.DISABLED)
        }
        session.configure(config)
    }

    override fun onPause() {
        super.onPause()
        if (arCoreSessionHelper.session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper?.onPause()
            surfaceView.onPause()
            arCoreSessionHelper.session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            results: IntArray
    ) {
        if (!GeoPermissionsHelper.hasGeoPermissions(requireActivity())) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(
                            requireActivity(),
                            requireContext().getString(R.string.permission_cl_needed),
                            Toast.LENGTH_LONG
                    )
                    .show()
            if (!GeoPermissionsHelper.shouldShowRequestPermissionRationale(requireActivity())) {
                // Permission denied with checking "Do not ask again".
                GeoPermissionsHelper.launchPermissionSettings(requireActivity())
            }
            requireActivity().finish()
        }
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an
        // IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(requireContext())
            planeRenderer.createOnGlThread(requireContext(), "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(requireContext())
            virtualObject.createOnGlThread(requireContext(), "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
            virtualObjectShadow.createOnGlThread(
                    requireContext(),
                    "models/andy_shadow.obj",
                    "models/andy_shadow.png"
            )
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read an asset file", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        displayRotationHelper?.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {

        Log.d(TAG, "session tracking state = " + arCoreSessionHelper.session?.earth?.trackingState)

        if(arCoreSessionHelper.session?.earth?.trackingState === TrackingState.STOPPED) {
            Log.e(TAG, "session stopped::" + arCoreSessionHelper.session?.earth?.earthState)
        }

        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (arCoreSessionHelper.session == null) {
            return
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper?.updateSessionIfNeeded(arCoreSessionHelper.session!!)
        try {
            arCoreSessionHelper.session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = arCoreSessionHelper.session!!.update()
            cloudAnchorManager.onUpdate()
            camera = frame.camera

            // Handle one tap per frame.
            handleTap(frame, camera)

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper?.updateKeepScreenOnFlag(camera.trackingState)

            // If not tracking, don't draw 3D objects, show tracking failure reason instead.
            if (camera.trackingState == TrackingState.PAUSED) {
                messageSnackbarHelper.showMessage(
                        requireActivity(),
                        TrackingStateHelper.getTrackingFailureReasonString(camera)
                )
                return
            }

            // Get projection matrix.
            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

            // Get camera matrix and draw.
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            // Compute lighting from average intensity of the image.
            // The first three components are color scaling factors.
            // The last one is the average pixel intensity in gamma space.
            val colorCorrectionRgba = FloatArray(4)
            frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)
            frame.acquirePointCloud().use { pointCloud ->
                pointCloudRenderer.update(pointCloud)
                pointCloudRenderer.draw(viewmtx, projmtx)
            }

            // No tracking error at this point. If we didn't detect any plane, show searchingPlane
            // message.
            if (!hasTrackingPlane()) {
                messageSnackbarHelper.showMessage(requireActivity(), requireContext().getString(R.string.searching_plane_message))
            }

            // Visualize planes.
            planeRenderer.drawPlanes(
                    arCoreSessionHelper.session!!.getAllTrackables(Plane::class.java),
                    camera.displayOrientedPose,
                    projmtx
            )

            if (currentAnchor != null && currentAnchor!!.trackingState == TrackingState.TRACKING) {
                currentAnchor!!.pose.toMatrix(anchorMatrix, 0)
                // Update and draw the model and its shadow.
                virtualObject.updateModelMatrix(anchorMatrix, 1f)
                virtualObjectShadow.updateModelMatrix(anchorMatrix, 1f)
                virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, andyColor)
                virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, andyColor)
            }

        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private fun handleTap(frame: Frame, camera: Camera) {
        if (currentAnchor != null) {
            return // Do nothing if there was already an anchor.
        }
        val tap: MotionEvent? = tapHelper?.poll()
        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            for (hit in frame.hitTest(tap)) {
                // Check if any plane was hit, and if it was hit inside the plane polygon
                val trackable = hit.trackable
                // Creates an anchor if a plane or an oriented point was hit.
                if ((trackable is Plane &&
                                trackable.isPoseInPolygon(hit.hitPose) &&
                                PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) >
                                        0) ||
                                (trackable is Point &&
                                        trackable.orientationMode ==
                                                Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
                ) {
                    // Hits are sorted by depth. Consider only closest hit on a plane or oriented
                    // point.

                    // Adding an Anchor tells ARCore that it should track this position in
                    // space. This anchor is created on the Plane to place the 3D model
                    // in the correct position relative both to the world and to the plane.
                    currentAnchor = hit.createAnchor()
                    // anchorsList.add(hit.createAnchor())
                    currentAnchorListIndex += 1
                    break
                }
            }
        }
    }

    @Synchronized
    private fun onHostedAnchorAvailable(anchor: Anchor) {
        val cloudState = anchor.cloudAnchorState

        if (cloudState == CloudAnchorState.SUCCESS) {

            messageSnackbarHelper.showMessage(
                activity,
                requireContext().getString(R.string.anchor_hosted, anchor.cloudAnchorId)
            )

            // FIXME: if we use here the cloud anchor back from api callback, the object is moved and it lose its correct position
             currentAnchor = anchor

        } else {
            messageSnackbarHelper.showError(activity, requireContext().getString(R.string.error_hosting, cloudState))
        }
    }

    /** Checks if we detected at least one plane. */
    private fun hasTrackingPlane(): Boolean {
        for (plane in arCoreSessionHelper.session!!.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState == TrackingState.TRACKING) {
                return true
            }
        }
        return false
    }

    @Synchronized
    private fun onClearButtonPressed() {
        // Clear the anchor from the scene.
        cloudAnchorManager.clearListeners()
        resolveButton.isEnabled = true
        currentAnchor = null
        Log.d(TAG, "arsample:: clear anchorsList")
    }

    @Synchronized
    private fun onSaveButtonPressed() {
        try {
            val earth = arCoreSessionHelper.session?.earth ?: return

            if (earth.earthState != Earth.EarthState.ENABLED) {
                Log.d(TAG, "earth state error::" + earth.earthState)
                return
            }
            if (earth.trackingState != TrackingState.TRACKING) {
                Log.d(TAG, "save tracking state error::" + earth.trackingState)
                Log.d(TAG, "earth.earthState::" + earth.earthState)
                return
            }

            earthAnchor?.detach()

            val cameraGeospatialPose: GeospatialPose = earth.cameraGeospatialPose

            val altitude =
                    cameraGeospatialPose.altitude +
                            camera.pose.qz()
            Log.d(
                    TAG,
                    "arsample::Camera GPS position: lat=" +
                            cameraGeospatialPose.latitude +
                            ", lon=" +
                            cameraGeospatialPose.longitude +
                            ",alt=" +
                            cameraGeospatialPose.altitude
            )
            Log.d(TAG, "arsample::Calculate object GPS position")

            Log.d(
                    TAG,
                    "arsample::camera coordinates = " +
                            cameraGeospatialPose.latitude +
                            "," +
                            cameraGeospatialPose.longitude +
                            ", " +
                            altitude
            )
            Log.d(
                    TAG,
                    "arsample::anchor position=" +
                            currentAnchor!!.pose.qx() +
                            "," +
                            currentAnchor!!.pose.qy() +
                            "," +
                            currentAnchor!!.pose.qz()
            )

            val calcPosition =
                    GeoUtilityHelpers.calculateObjectPosition(
                            earth.cameraGeospatialPose,
                            camera.pose,
                            currentAnchor!!
                    )

            Log.d(
                    TAG,
                    "arsample::anchor calc position=" +
                            calcPosition.x +
                            "," +
                            calcPosition.y +
                            "," +
                            earth.cameraGeospatialPose.altitude +
                            currentAnchor!!.pose.qz()
            )

            val geospatialPose = earth.getGeospatialPose(currentAnchor!!.pose);

            earthAnchor =
                earth.createAnchor(geospatialPose.latitude, geospatialPose.longitude, geospatialPose.altitude, geospatialPose.eastUpSouthQuaternion)


            messageSnackbarHelper.showMessage(requireActivity(), "Now hosting anchor...");
            cloudAnchorManager.hostCloudAnchor(arCoreSessionHelper.session, earthAnchor, /* ttl= */ 300, this::onHostedAnchorAvailable)
            currentAnchorData = AnchorData("", earthAnchor!!)
        } catch (exc: Exception) {
            exc.message?.let { Log.e(TAG, "arsample::" + it) }
            messageSnackbarHelper.showError(requireActivity(), requireContext().getString(R.string.anchor_not_saved))
        }
    }

    @Synchronized
    private fun onResolveButtonPressed() {
        val dialog = ResolveDialogFragment.createWithOkListener(::onShortCodeEntered)
        dialog.show(requireActivity().supportFragmentManager, "Resolve")
    }

    @Synchronized
    private fun onShortCodeEntered(cloudAnchorId: String) {
        if (cloudAnchorId.isEmpty()) {
            messageSnackbarHelper.showMessage(
                    activity,
                    requireContext().getString(R.string.anchor_not_found, cloudAnchorId)
            )
            return
        }

        cloudAnchorManager.resolveCloudAnchor(arCoreSessionHelper.session, cloudAnchorId) {
                anchor: Anchor? ->
            onResolvedAnchorAvailable(anchor!!, cloudAnchorId)
        }
    }

    @Synchronized
    private fun onResolvedAnchorAvailable(anchor: Anchor, cloudAnchorId: String) {
        val cloudState = anchor.cloudAnchorState
        if (cloudState == CloudAnchorState.SUCCESS) {
            messageSnackbarHelper.showMessage(
                    activity,
            requireContext().getString(R.string.anchor_resolved, cloudAnchorId)
            )
            Log.d(
                    TAG,
                    "resolve anchor::" +
                            anchor.cloudAnchorId +
                            ":" +
                            anchor.pose?.toString() +
                            ":"
            )

            currentAnchor = anchor
        } else {
            messageSnackbarHelper.showMessage(
                activity,
                requireContext().getString(R.string.error_anchor_resolving, cloudAnchorId, cloudState.toString())
            )
        }
    }

    companion object {
        private const val TAG: String = "SceneArFragment"
    }
}
