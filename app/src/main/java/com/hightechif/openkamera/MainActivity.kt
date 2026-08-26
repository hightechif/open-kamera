/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Fragment
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PorterDuff
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.preference.Preference
import android.preference.PreferenceFragment
import android.preference.PreferenceFragment.OnPreferenceStartFragmentCallback
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.renderscript.RenderScript
import android.speech.tts.TextToSpeech
import android.text.Html
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.util.Log
import android.util.SizeF
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.View.OnLongClickListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hightechif.openkamera.MyApplicationInterface.PhotoMode
import com.hightechif.openkamera.audio.AudioListener
import com.hightechif.openkamera.audio.MyAudioTriggerListenerCallback
import com.hightechif.openkamera.audio.SoundPoolManager
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.CameraController.Facing
import com.hightechif.openkamera.cameracontroller.CameraController.TonemapProfile
import com.hightechif.openkamera.cameracontroller.CameraControllerManager.CameraInfo
import com.hightechif.openkamera.cameracontroller.CameraControllerManager2
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.preferences.MyPreferenceFragment
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preferences.SettingsManager
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.preview.VideoProfile
import com.hightechif.openkamera.remotecontrol.BluetoothRemoteControl
import com.hightechif.openkamera.sensors.LocationSupplier
import com.hightechif.openkamera.sensors.MagneticSensor
import com.hightechif.openkamera.storage.SaveLocationHistory
import com.hightechif.openkamera.storage.StorageUtils
import com.hightechif.openkamera.system.KeyguardUtils
import com.hightechif.openkamera.system.MyTileService
import com.hightechif.openkamera.system.MyTileServiceFrontCamera
import com.hightechif.openkamera.system.MyTileServiceVideo
import com.hightechif.openkamera.system.PermissionHandler
import com.hightechif.openkamera.ui.CameraUiEffect
import com.hightechif.openkamera.ui.CameraUiEvent
import com.hightechif.openkamera.ui.CameraViewModel
import com.hightechif.openkamera.ui.DrawPreview
import com.hightechif.openkamera.ui.FolderChooserDialog
import com.hightechif.openkamera.ui.MainUI
import com.hightechif.openkamera.ui.ManualSeekbars
import com.hightechif.openkamera.ui.SettingsViewModel
import com.hightechif.openkamera.utils.MultiCamHandler
import com.hightechif.openkamera.utils.MyDebug
import com.hightechif.openkamera.utils.SaveLocationHandler
import com.hightechif.openkamera.utils.TextFormatter
import com.hightechif.openkamera.utils.ToastBoxer
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.DecimalFormat
import java.util.Hashtable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.inject.Inject
import kotlin.concurrent.Volatile
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The main Activity for Open Kamera.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity(), OnPreferenceStartFragmentCallback {
    val cameraViewModel: CameraViewModel by viewModels()
    val settingsViewModel: SettingsViewModel by viewModels()

    @Inject lateinit var settingsRepository: ISettingsRepository
    @Inject lateinit var mediaRepository: IMediaRepository
    @Inject lateinit var locationRepository: ILocationRepository
    @Inject lateinit var sensorRepository: ISensorRepository

    var isAppPaused: Boolean = true
        private set

    private lateinit var mSensorManager: SensorManager
    private var mSensorAccelerometer: Sensor? = null

    // components: always non-null (after onCreate())
    lateinit var bluetoothRemoteControl: BluetoothRemoteControl
    lateinit var permissionHandler: PermissionHandler
    lateinit var settingsManager: SettingsManager
    lateinit var mainUI: MainUI
    lateinit var manualSeekbars: ManualSeekbars
    lateinit var applicationInterface: MyApplicationInterface
    lateinit var textFormatter: TextFormatter
    lateinit var soundPoolManager: SoundPoolManager
    lateinit var magneticSensor: MagneticSensor
    lateinit var multiCamHandler: MultiCamHandler

    //private val speechControl
    lateinit var preview: Preview
    private lateinit var orientationEventListener: OrientationEventListener
    private lateinit var layoutChangeListener: OnLayoutChangeListener
    private var largeHeapMemory = 0
    private var supportsAutoStabilise = false
    private var supportsForceVideo4k = false
    private var supportsCamera2 = false
    lateinit var saveLocationHandler: SaveLocationHandler
    val saveLocationHistory: SaveLocationHistory
        get() = saveLocationHandler.saveLocationHistory
    var saveLocationHistorySaf: SaveLocationHistory?
        get() = saveLocationHandler.saveLocationHistorySAF
        set(value) {
            saveLocationHandler.saveLocationHistorySAF = value
        }
    private var safDialogFromPreferences =
        false // if a SAF dialog is opened, this records whether we opened it from the Preferences
    var isCameraInBackground: Boolean =
        false // whether the camera is covered by a fragment/dialog (such as settings or folder picker)
        private set
    private lateinit var gestureDetector: GestureDetector

    /** Whether the screen is locked (see lockScreen()).
     */
    var isScreenLocked: Boolean =
        false // whether screen is "locked" - this is Open Kamera's own lock to guard against accidental presses, not the standard Android lock
        private set
    private val preloadedBitmapResources: MutableMap<Int, Bitmap> = Hashtable()
    private var gallerySaveAnim: ValueAnimator? = null
    private var lastContinuousFastBurst =
        false // whether the last photo operation was a continuousFastBurst
    private var updateGalleryFuture: Future<*>? = null

    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechSuccess = false

    private var audioListener: AudioListener? = null // may be null - created when needed

    /** Returns whether we are always running in edge-to-edge mode. (If false, we may still sometimes
     * run edge-to-edge.)
     */
    //private boolean uiPlacementRight = true
    //private final boolean edgeToEdgeMode = false // whether running always in edge-to-edge mode
    //private final boolean edgeToEdgeMode = true // whether running always in edge-to-edge mode
    val edgeToEdgeMode: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM // whether running always in edge-to-edge mode
    private var wantNoLimits = false // whether we want to run with FLAG_LAYOUT_NO_LIMITS
    private var setWindowInsetsListener =
        false // whether we've enabled a setOnApplyWindowInsetsListener()

    // gap for navigation bar along bottom (portrait) or right (landscape)
    private var _navigationGap = 0
    val navigationGap: Int
        get() = if (wantNoLimits || edgeToEdgeMode) _navigationGap else 0

    // gap for navigation bar along left (portrait) or bottom (landscape) only set for edgeToEdgeMode==true
    private var _navigationGapLandscape = 0
    val navigationGapLandscape: Int
        get() = if (edgeToEdgeMode) _navigationGapLandscape else 0

    // gap for navigation bar along right (portrait) or top (landscape) only set for edgeToEdgeMode==true
    private var _navigationGapReverseLandscape = 0
    val navigationGapReverseLandscape: Int
        get() = if (edgeToEdgeMode) _navigationGapReverseLandscape else 0

    @Volatile
    var testSetShowUnderNavigation: Boolean =
        false // test flag, the value of enable for the last call of showUnderNavigation() (or false if not yet called)

    /** Whether this is a multi camera device, whether the user preference is set to enable
     * the multi-camera button.
     */
    // whether this is a multi-camera device (note, this isn't simply having more than 1 camera, but also having more than one with the same facing)
    // note that in most cases, code should check the MULTI_CAM_BUTTON_PREFERENCE_KEY preference as well as the isMultiCam flag,
    // this can be done via isMultiCamEnabled().
    var isMultiCam: Boolean = false
        private set

    // These lists are lists of camera IDs with the same "facing" (front, back or external).
    // Only initialized if isMultiCam==true.
    private lateinit var backCameraIds: MutableList<Int>
    private lateinit var frontCameraIds: MutableList<Int>
    private lateinit var otherCameraIds: MutableList<Int>

    private val switchVideoToast: ToastBoxer = ToastBoxer()
    private val screenLockedToast: ToastBoxer = ToastBoxer()
    private val stampToast: ToastBoxer = ToastBoxer()
    private val changedAutoStabiliseToast: ToastBoxer = ToastBoxer()
    private val whiteBalanceLockToast: ToastBoxer = ToastBoxer()
    private val exposureLockToast: ToastBoxer = ToastBoxer()
    private val audioControlToast: ToastBoxer = ToastBoxer()
    private val storeLocationToast: ToastBoxer = ToastBoxer()
    private var blockStartupToast =
        false // used when returning from Settings/Popup - if we're displaying a toast anyway, don't want to display the info toast too
    private var pushInfoToastText: String? =
        null // can be used to "push" extra text to the info text for showPhotoVideoToast()
    private var pushSwitchedCamera =
        false // whether to display animation for switching front/back cameras

    // for testing must be volatile for test project reading the state
    // n.b., avoid using static, as static variables are shared between different instances of an application,
    // and won't be reset in subsequent tests in a suite!
    var isTest: Boolean = false // whether called from OpenKamera.test testing

    @Volatile
    var galleryBitmap: Bitmap? = null

    @Volatile
    var testLowMemory: Boolean = false

    @Volatile
    var testHaveAngle: Boolean = false

    @Volatile
    var testAngle: Float = 0f

    @Volatile
    var testLastSavedImageuri: Uri? =
        null // uri of last image set if using scoped storage OR using SAF

    @Volatile
    var testLastSavedImage: String? =
        null // filename (including full path) of last image set if not using scoped storage nor using SAF (i.e., writing using File API)

    @Volatile
    var testSaveSettingsFile: String? = null

    var waterDensity: Float = 1.0f
        private set

    // handling for lockToLandscape==false:
    enum class SystemOrientation {
        LANDSCAPE,
        PORTRAIT,
        REVERSE_LANDSCAPE
    }

    private var displayListener: MyDisplayListener? = null

    private var hasCachedSystemOrientation = false
    private lateinit var cachedSystemOrientation: SystemOrientation

    private var hasOldSystemOrientation = false
    private lateinit var oldSystemOrientation: SystemOrientation

    private var hasCachedDisplayRotation = false
    private var cachedDisplayRotationTimeMs: Long = 0
    private var cachedDisplayRotation = 0

    // mapping from exposureSeekbar progress value to preview exposure compensation
    var exposureSeekbarValues: MutableList<Int>? = null

    // index in exposureSeekbarValues that maps to zero preview exposure compensation
    var exposureSeekbarProgressZero: Int = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "onCreate: $this")
            debugTime = System.currentTimeMillis()
        }
        activityCount++
        if (MyDebug.LOG) Log.d(TAG, "activity_count: $activityCount")
        //EdgeToEdge.enable(this, SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT)) // test edge-to-edge on pre-Android 15
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        PreferenceManager.setDefaultValues(
            this,
            R.xml.preferences,
            false
        ) // initialize any unset preferences to their default values
        if (MyDebug.LOG) Log.d(
            TAG,
            "onCreate: time after setting default preference values: " + (System.currentTimeMillis() - debugTime)
        )

        if (intent != null && intent.extras != null) {
            // whether called from testing
            isTest = intent.extras!!.getBoolean("test_project")
            if (MyDebug.LOG) Log.d(TAG, "is_test: $isTest")
        }
        /*if( getIntent() != null && getIntent().getExtras() != null ) {
            // whether called from Take Photo widget
            if( MyDebug.LOG )
                Log.d(TAG, "takePhoto?: " + getIntent().getExtras().getBoolean(TakePhoto.TAKE_PHOTO))
        }*/
        if (MyDebug.LOG) {
            // whether called from Take Photo widget
            Log.d(TAG, "take_photo?: " + TakePhoto.TAKE_PHOTO)
        }
        if (intent != null && intent.action != null) {
            // invoked via the manifest shortcut?
            if (MyDebug.LOG) Log.d(TAG, "shortcut: " + intent.action)
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // determine whether we should support "auto stabilize" feature
        // risk of running out of memory on lower end devices, due to manipulation of large bitmaps
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (MyDebug.LOG) {
            Log.d(TAG, "large max memory = " + activityManager.largeMemoryClass + "MB")
        }
        largeHeapMemory = activityManager.largeMemoryClass
        if (largeHeapMemory >= 128) {
            supportsAutoStabilise = true
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_auto_stabilise? $supportsAutoStabilise"
        )

        // hack to rule out phones unlikely to have 4K video, so no point even offering the option!
        // both S5 and Note 3 have 128MB standard and 512MB large heap (tested via Samsung RTL), as does Galaxy K Zoom
        if (activityManager.largeMemoryClass >= 512) {
            supportsForceVideo4k = true
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_force_video_4k? $supportsForceVideo4k"
        )

        // set up components
        bluetoothRemoteControl = BluetoothRemoteControl(this)
        permissionHandler = PermissionHandler(this)
        settingsManager = SettingsManager(this)
        mainUI = MainUI(this)
        manualSeekbars = ManualSeekbars()
        applicationInterface = MyApplicationInterface(
            this,
            savedInstanceState,
            settingsRepository,
            mediaRepository,
            locationRepository,
            sensorRepository
        )
        if (MyDebug.LOG) Log.d(
            TAG,
            "onCreate: time after creating application interface: " + (System.currentTimeMillis() - debugTime)
        )
        textFormatter = TextFormatter(this)
        soundPoolManager = SoundPoolManager(this)
        magneticSensor = MagneticSensor(this)

        //speechControl = new SpeechControl(this)

        // determine whether we support Camera2 API
        // must be done before setDeviceDefaults()
        initCamera2Support()

        // set some per-device defaults
        // must be done before creating the Preview (as setDeviceDefaults() may set Camera2 API)
        val hasDoneFirstTime = sharedPreferences.contains(PreferenceKeys.FIRST_TIME_PREFERENCE_KEY)
        if (MyDebug.LOG)
            Log.d(TAG, "hasDoneFirstTime: $hasDoneFirstTime")
        if (!hasDoneFirstTime) {
            // must be done after initCamera2Support()
            setDeviceDefaults()
        }

        val settingsIsOpen = settingsIsOpen()
        if (MyDebug.LOG)
            Log.d(TAG, "settings_is_open?: $settingsIsOpen")
        // settings_is_open==true can happen if application is recreated when settings is open
        // to reproduce: go to settings, then turn screen off and on (and unlock)
        if (!settingsIsOpen) {
            // set up window flags for normal operation
            setWindowFlagsForCamera()
        }
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after setting window flags: " + (System.currentTimeMillis() - debugTime)
            )

        saveLocationHandler = SaveLocationHandler(this)
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after updating folder history: " + (System.currentTimeMillis() - debugTime)
            )

        // set up sensors
        mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // accelerometer sensor (for device orientation)
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            if (MyDebug.LOG)
                Log.d(TAG, "found accelerometer")
            mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } else {
            if (MyDebug.LOG)
                Log.d(TAG, "no support for accelerometer")
        }
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after creating accelerometer sensor: " + (System.currentTimeMillis() - debugTime)
            )

        // magnetic sensor (for compass direction)
        magneticSensor.initSensor(mSensorManager)
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after creating magnetic sensor: " + (System.currentTimeMillis() - debugTime)
            )

        // clear any seek bars (just in case??)
        mainUI.closeExposureUI()

        // set up the camera and its preview
        preview = Preview(applicationInterface, (this.findViewById(R.id.preview)))
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after creating preview: " + (System.currentTimeMillis() - debugTime)
            )

        if (settingsIsOpen) {
            // must be done after creating preview
            setWindowFlagsForSettings()
        }

        run {
            // don't show orientation animations
            // must be done after creating Preview (so we know if Camera2 API or not)
            val layout = window.attributes
            // If locked to landscape, ROTATION_ANIMATION_SEAMLESS/JUMPCUT has the problem that when going to
            // Settings in portrait, we briefly see the UI change - this is because we set the flag
            // to no longer lock to landscape, and that change happens too quickly.
            // This isn't a problem when lock_to_landscape==false, and we want
            // ROTATION_ANIMATION_SEAMLESS so that there is no/minimal pause from the preview when
            // rotating the device. However, if using old camera API, we get an ugly transition with
            // ROTATION_ANIMATION_SEAMLESS (probably related to not using TextureView?)
            if (LOCK_TO_LANDSCAPE || !preview.usingCamera2API())
                layout.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                layout.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
            else
                layout.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
            window.attributes = layout
        }

        // Setup multi-camera buttons (must be done after creating preview so we know which Camera API is being used,
        // and before initializing on-screen visibility).
        this.multiCamHandler = MultiCamHandler(preview.cameraControllerManager)

        // initialize on-screen button visibility
        val switchCameraButton = findViewById<ImageButton>(R.id.switch_camera)
        val nCameras = preview.cameraControllerManager.numberOfCameras
        switchCameraButton.visibility = if (nCameras > 1) View.VISIBLE else View.GONE
        // switchMultiCameraButton visibility updated below in mainUI.updateOnScreenIcons(), as it also depends on user preference
        val speechRecognizerButton = findViewById<ImageButton>(R.id.audio_control)
        speechRecognizerButton.visibility =
            View.GONE // disabled by default, until the speech recognizer is created
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after setting button visibility: " + (System.currentTimeMillis() - debugTime)
            )
        val pauseVideoButton = findViewById<ImageButton>(R.id.pause_video)
        pauseVideoButton.visibility = View.GONE
        val takePhotoVideoButton = findViewById<ImageButton>(R.id.take_photo_when_video_recording)
        takePhotoVideoButton.visibility = View.GONE
        val cancelPanoramaButton = findViewById<ImageButton>(R.id.cancel_panorama)
        cancelPanoramaButton.visibility = View.GONE

        // We initialize optional controls to invisible/gone, so they don't show while the camera is opening - the actual visibility is
        // set in cameraSetup().
        // Note that ideally we'd set this in the XML, but doing so for R.id.zoom causes a crash on Galaxy Nexus startup beneath
        // setContentView()!
        // To be safe, we also do so for take_photo and zoom_seekbar (we already know we've had no reported crashes for focus_seekbar,
        // however).
        val takePhotoButton = findViewById<ImageButton>(R.id.take_photo)
        takePhotoButton.visibility = View.INVISIBLE
        val zoomSeekbar = findViewById<SeekBar>(R.id.zoom_seekbar)
        zoomSeekbar.visibility = View.INVISIBLE

        // initialize state of on-screen icons
        mainUI.getOnScreenIcons().updateOnScreenIcons()

        if (LOCK_TO_LANDSCAPE) {
            // listen for orientation event change (only required if lock_to_landscape==true
            // (MainUI.onOrientationChanged() does nothing if lock_to_landscape==false)
            orientationEventListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(p0: Int) {
                    mainUI.onOrientationChanged(p0)
                }
            }
            if (MyDebug.LOG)
                Log.d(
                    TAG,
                    "onCreate: time after setting orientation event listener: " + (System.currentTimeMillis() - debugTime)
                )
        }

        layoutChangeListener =
            OnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (MyDebug.LOG)
                    Log.d(TAG, "onLayoutChange")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
                    val displaySize = Point()
                    applicationInterface.getDisplaySize(displaySize, true)
                    if (MyDebug.LOG) {
                        Log.d(TAG, "    display width: " + displaySize.x)
                        Log.d(TAG, "    display height: " + displaySize.y)
                        Log.d(TAG, "    layoutUI display width: " + mainUI.layoutUI_display_w)
                        Log.d(TAG, "    layoutUI display height: " + mainUI.layoutUI_display_h)
                    }
                    // We need to call layoutUI when the window is resized without an orientation change -
                    // this can happen in split-screen or multi-window mode, where onConfigurationChanged
                    // is not guaranteed to be called.
                    // We check against the size of when layoutUI was last called, to avoid repeated calls
                    // when the resize is due to the device rotating and onConfigurationChanged is called -
                    // in fact we'd have a problem of repeatedly calling layoutUI, since doing layoutUI
                    // causes onLayoutChange() to be called again.
                    if (displaySize.x != mainUI.layoutUI_display_w || displaySize.y != mainUI.layoutUI_display_h) {
                        if (MyDebug.LOG)
                            Log.d(TAG, "call layoutUI due to resize")
                        mainUI.layoutUI()
                    }
                }
            }

        // set up take photo long click
        takePhotoButton.setOnLongClickListener(object : OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                if (!allowLongPress()) {
                    // return false, so a regular click will still be triggered when the user releases the touch
                    return false
                }
                return longClickedTakePhoto()
            }
        })
        // set up on touch listener so we can detect if we've released from a long click
        takePhotoButton.setOnTouchListener { view, motionEvent ->

            // the suppressed warning ClickableViewAccessibility suggests calling view.performClick for ACTION_UP, but this
            // results in an additional call to clickedTakePhoto() - that is, if there is no long press, we get two calls to
            // clickedTakePhoto instead one-one and if there is a long press, we get one call to clickedTakePhoto where
            // there should be none.
            if (motionEvent.action == MotionEvent.ACTION_UP) {
                if (MyDebug.LOG)
                    Log.d(TAG, "takePhotoButton ACTION_UP")
                takePhotoButtonLongClickCancelled()
                if (MyDebug.LOG)
                    Log.d(TAG, "takePhotoButton ACTION_UP done")
            }
            false
        }

        // set up gallery button long click
        val galleryButton = findViewById<ImageButton>(R.id.gallery)
        galleryButton.setOnLongClickListener(object : OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                if (!allowLongPress()) {
                    // return false, so a regular click will still be triggered when the user releases the touch
                    return false
                }
                //preview.showToast(null, "Long click")
                longClickedGallery()
                return true
            }
        })

        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after setting long click listeners: " + (System.currentTimeMillis() - debugTime)
            )

        // listen for gestures
        gestureDetector = GestureDetector(this, MyGestureDetector())
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after creating gesture detector: " + (System.currentTimeMillis() - debugTime)
            )

        setupSystemUiVisibilityListener()
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after setting system ui visibility listener: " + (System.currentTimeMillis() - debugTime)
            )

        // show "about" dialog for first time use
        if (!hasDoneFirstTime) {
            if (!isTest) {
                val alertDialog = AlertDialog.Builder(this)
                alertDialog.setTitle(R.string.app_name)
                alertDialog.setMessage(R.string.intro_text)
                alertDialog.setPositiveButton(android.R.string.ok, null)
                alertDialog.setNegativeButton(R.string.preference_online_help) { dialog, which ->
                    if (MyDebug.LOG)
                        Log.d(TAG, "online help")
                    launchOnlineHelp()
                }
                alertDialog.show()
            }

            setFirstTimeFlag()
        }

        run {
            // handle What's New dialog
            var versionCode = -1
            try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                versionCode = pInfo.versionCode
            } catch (e: PackageManager.NameNotFoundException) {
                MyDebug.logStackTrace(
                    TAG,
                    "NameNotFoundException exception trying to get version number",
                    e
                )
            }
            if (versionCode != -1) {
                val latestVersion =
                    sharedPreferences.getInt(PreferenceKeys.LATEST_VERSION_PREFERENCE_KEY, 0)
                if (MyDebug.LOG) {
                    Log.d(TAG, "version_code: $versionCode")
                    Log.d(TAG, "latest_version: $latestVersion")
                }
                //final boolean whats_new_enabled = false
                val whatsNewEnabled = true
                if (whatsNewEnabled) {
                    // whats_new_version is the version code that the What's New text is written for. Normally it will equal the
                    // current release (version_code), but it some cases we may want to leave it unchanged.
                    // E.g., we have a "What's New" for 1.44 (64), but then push out a quick fix for 1.44.1 (65). We don't want to
                    // show the dialog again to people who already received 1.44 (64), but we still want to show the dialog to people
                    // upgrading from earlier versions.
                    var whatsNewVersion = 94 // 1.56
                    whatsNewVersion =
                        whatsNewVersion.coerceAtMost(versionCode) // whats_new_version should always be <= version_code, but just in case!
                    if (MyDebug.LOG) {
                        Log.d(TAG, "whats_new_version: $whatsNewVersion")
                    }
                    val forceWhatsNew = false
                    //final boolean force_whats_new = true // for testing
                    val allowShowWhatsNew = sharedPreferences.getBoolean(
                        PreferenceKeys.SHOW_WHATS_NEW_PREFERENCE_KEY,
                        true
                    )
                    if (MyDebug.LOG)
                        Log.d(TAG, "allow_show_whats_new: $allowShowWhatsNew")
                    // don't show What's New if this is the first time the user has run
                    if (hasDoneFirstTime && allowShowWhatsNew && (forceWhatsNew || whatsNewVersion > latestVersion)) {
                        val alertDialog = AlertDialog.Builder(this)
                        alertDialog.setTitle(R.string.whats_new)
                        alertDialog.setMessage(R.string.whats_new_text)
                        alertDialog.setPositiveButton(android.R.string.ok, null)
                        alertDialog.show()
                    }
                }
                // We set the latest_version whether the dialog is shown - if we showed the first time dialog, we don't
                // want to then show the What's New dialog next time we run! Similarly, if the user had disabled showing the dialog,
                // but then enables it, we still shouldn't show the dialog until the new time Open Kamera upgrades.
                sharedPreferences.edit {
                    putInt(PreferenceKeys.LATEST_VERSION_PREFERENCE_KEY, versionCode)
                }
            }
        }

        setModeFromIntents(savedInstanceState)

        // load icons
        preloadIcons(R.array.flash_icons)
        preloadIcons(R.array.focus_mode_icons)
        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: time after preloading icons: " + (System.currentTimeMillis() - debugTime)
            )

        // initialize text to speech engine
        textToSpeechSuccess = false
        // run in separate thread to not delay startup time
        thread {
            textToSpeech = TextToSpeech(this@MainActivity) { status ->
                if (MyDebug.LOG) Log.d(TAG, "TextToSpeech initialised")

                if (status == TextToSpeech.SUCCESS) {
                    textToSpeechSuccess = true
                    if (MyDebug.LOG) Log.d(TAG, "TextToSpeech succeeded")
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "TextToSpeech failed")
                }
            }
        }

        // handle on back behaviour
        popupOnBackPressedCallback = PopupOnBackPressedCallback(false)
        onBackPressedDispatcher.addCallback(this, popupOnBackPressedCallback)
        pausePreviewOnBackPressedCallback = PausePreviewOnBackPressedCallback(false)
        onBackPressedDispatcher.addCallback(this, pausePreviewOnBackPressedCallback)
        screenLockOnBackPressedCallback = ScreenLockOnBackPressedCallback(false)
        onBackPressedDispatcher.addCallback(this, screenLockOnBackPressedCallback)

        // create notification channel - only needed on Android 8+
        // update: notifications now removed due to needing permissions on Android 13+
        /*if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            CharSequence name = "Open Kamera Image Saving"
            String description = "Notification channel for processing and saving images in the background"
            int importance = NotificationManager.IMPORTANCE_LOW
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance)
            channel.setDescription(description)
            // Register the channel with the system you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class)
            notificationManager.createNotificationChannel(channel)
        }*/

        // so we get the icons rotation even when rotating for the first time - see onSystemOrientationChanged
        this.hasOldSystemOrientation = true
        this.oldSystemOrientation = systemOrientation

        observeCameraViewModel()

        if (MyDebug.LOG)
            Log.d(
                TAG,
                "onCreate: total time for Activity startup: " + (System.currentTimeMillis() - debugTime)
            )
    }

    private fun observeCameraViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    cameraViewModel.uiState.collect { state ->
                        // 1. Shutter button / Recording indicator
                        val takePhotoButton = findViewById<ImageButton>(R.id.take_photo)
                        if (takePhotoButton != null) {
                            if (state.isRecording) {
                                takePhotoButton.setImageResource(R.drawable.take_video_recording)
                                takePhotoButton.contentDescription = getString(R.string.stop_video)
                            } else if (state.captureMode == CaptureMode.VIDEO) {
                                takePhotoButton.setImageResource(R.drawable.take_video_selector)
                                takePhotoButton.contentDescription = getString(R.string.start_video)
                            } else {
                                takePhotoButton.setImageResource(R.drawable.take_photo_selector)
                                takePhotoButton.contentDescription = getString(R.string.take_photo)
                            }
                        }

                        // 2. Gallery Thumbnail URI
                        state.latestThumbnailUri?.let { uri ->
                            try {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val bitmap = BitmapFactory.decodeStream(stream)
                                    if (bitmap != null) {
                                        val galleryButton = findViewById<ImageButton>(R.id.gallery)
                                        galleryButton?.setImageBitmap(bitmap)
                                        galleryBitmap = bitmap
                                    }
                                }
                            } catch (_: Exception) {
                                // Fallback silently
                            }
                        }

                        // 3. Request update for preview overlays HUD
                        applicationInterface.drawPreview.updateSettings()
                    }
                }
                launch {
                    cameraViewModel.uiEffect.collect { effect ->
                        when (effect) {
                            is CameraUiEffect.ShowToast -> {
                                preview.showToast(null, effect.message)
                            }

                            is CameraUiEffect.Vibrate -> {
                                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }

                            is CameraUiEffect.NavigateToGallery -> {
                                clickedGallery(null)
                            }

                            is CameraUiEffect.OpenSettings -> {
                                clickedSettings(null)
                            }

                            is CameraUiEffect.ShowErrorDialog -> {
                                preview.showToast(null, "${effect.title}: ${effect.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    val isMultiCamEnabled: Boolean
        /** Whether this is a multi camera device, and the user preference is set to enable the multi-camera button.
         */
        get() {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            return isMultiCam && sharedPreferences.getBoolean(
                PreferenceKeys.MULTI_CAM_BUTTON_PREFERENCE_KEY,
                true
            )
        }

    private val actualCameraId: Int
        /* Returns the camera ID in use by the preview - or the one we requested, if the camera failed
                  * to open.
                  * Needed as Preview.cameraId returns 0 if cameraController==null, but if the camera
                  * fails to open, we want the switch camera icons to still work as expected!
                  */
        get() {
            return if (preview.cameraController == null) applicationInterface.getCameraIdPref()
            else preview.cameraId
        }

    /** Whether the icon switchMultiCamera should be displayed. This is if the following are all
     * true:
     * - The device is a multi camera device (MainActivity.isMultiCam==true).
     * - The user preference for using the separate icons is enabled
     * (PreferenceKeys.MULTI_CAM_BUTTON_PREFERENCE_KEY).
     * - For the current camera ID, there are at least two cameras with the same front/back/external
     * "facing" (e.g., imagine a device with two back cameras, but only one front camera - no point
     * showing the multi-cam icon for just a single logical front camera).
     * OR there are physical cameras for the current camera, and again the user preference
     * PreferenceKeys.MULTI_CAM_BUTTON_PREFERENCE_KEY is enabled.
     */
    fun showSwitchMultiCamIcon(): Boolean {
        if (preview.hasPhysicalCameras()) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            if (sharedPreferences.getBoolean(
                    PreferenceKeys.MULTI_CAM_BUTTON_PREFERENCE_KEY,
                    true
                )
            ) return true
        }
        if (isMultiCamEnabled) {
            val cameraId = actualCameraId
            when (preview.cameraControllerManager.getFacing(cameraId)) {
                Facing.FACING_BACK -> if (backCameraIds.size > 1) return true
                Facing.FACING_FRONT -> if (frontCameraIds.size > 1) return true
                else -> if (otherCameraIds.size > 1) return true
            }
        }
        return false
    }

    /** Whether user preference is set to allow long press actions.
     */
    private fun allowLongPress(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        return sharedPreferences.getBoolean(PreferenceKeys.ALLOW_LONG_PRESS_PREFERENCE_KEY, true)
    }

    /* This method sets the preference defaults which are set specific for a particular device.
     * This method should be called when Open Kamera is run for the very first time after installation,
     * or when the user has requested to "Reset settings".
     */
    fun setDeviceDefaults() {
        if (MyDebug.LOG) Log.d(TAG, "setDeviceDefaults")
        val isSamsung = Build.MANUFACTURER.lowercase().contains("samsung")
        //SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        //boolean isSamsung = Build.MANUFACTURER.toLowerCase(Locale.US).contains("samsung")
        //boolean isOneplus = Build.MANUFACTURER.toLowerCase(Locale.US).contains("oneplus")
        //boolean isNexus = Build.MODEL.toLowerCase(Locale.US).contains("nexus")
        //boolean isNexus6 = Build.MODEL.toLowerCase(Locale.US).contains("nexus 6")
        //boolean isPixelPhone = Build.DEVICE != null && Build.DEVICE.equals("sailfish")
        //boolean isPixelXlPhone = Build.DEVICE != null && Build.DEVICE.equals("marlin")
        /*if( MyDebug.LOG ) {
            //Log.d(TAG, "isSamsung? " + isSamsung)
            //Log.d(TAG, "isOneplus? " + isOneplus)
            //Log.d(TAG, "isNexus? " + isNexus)
            //Log.d(TAG, "isNexus6? " + isNexus6)
            //Log.d(TAG, "isPixelPhone? " + isPixelPhone)
            //Log.d(TAG, "isPixelXlPhone? " + isPixelXlPhone)
        }*/
        /*if( isSamsung || isOneplus ) {
            // The problems we used to have on Samsung Galaxy devices are now fixed, by setting
            // TEMPLATE_PREVIEW for the precaptureBuilder in CameraController2. This also fixes the
            // problems with OnePlus 3T having blue tinge if flash is on, and the scene is bright
            // enough not to need it
            if( MyDebug.LOG )
                Log.d(TAG, "set fake flash for camera2")
            SharedPreferences.Editor editor = sharedPreferences.edit()
            editor.putBoolean(PreferenceKeys.CAMERA2_FAKE_FLASH_PREFERENCE_KEY, true)
            editor.apply()
        }*/
        /*if( isNexus6 ) {
			// Nexus 6 captureBurst() started having problems with Android 7 upgrade - images appeared in wrong order (and with wrong order of shutter speeds in exif info), as well as problems with the camera failing with serious errors
			// we set this even for Nexus 6 devices not on Android 7, as at some point they'll likely be upgraded to Android 7
			// Update: now fixed in v1.37, this was due to bug where we set RequestTag.CAPTURE for all captures in takePictureBurstExpoBracketing(), rather than just the last!
			if( MyDebug.LOG )
				Log.d(TAG, "disable fast burst for camera2")
			SharedPreferences.Editor editor = sharedPreferences.edit()
			editor.putBoolean(PreferenceKeys.CAMERA2_FAST_BURST_PREFERENCE_KEY, false)
			editor.apply()
		}*/
        if (isSamsung && !isTest) {
            // Samsung Galaxy devices (including S10e, S24) have problems with HDR/expo - base images come out with wrong exposures.
            // This can be fixed by not using fast bast, allowing us to adjust the preview exposure to match.
            if (MyDebug.LOG) Log.d(TAG, "disable fast burst for camera2")
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            sharedPreferences.edit {
                putBoolean(PreferenceKeys.CAMERA2_FAST_BURST_PREFERENCE_KEY, false)
            }
        }
        if (supportsCamera2 && !isTest) {
            // n.b., when testing, we explicitly decide whether to run with Camera2 API or not
            val manager2 = CameraControllerManager2(this)
            val nCameras: Int = manager2.numberOfCameras
            var allSupportsCamera2 =
                true // whether all cameras have at least LIMITED support for Camera2 (risky to default to Camera2 if any cameras are LEGACY, as not easy to test such devices)
            var i = 0
            while (i < nCameras && allSupportsCamera2) {
                if (!manager2.allowCamera2Support(i)) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "camera $i doesn't have at least LIMITED support for Camera2 API"
                    )
                    allSupportsCamera2 = false
                }
                i++
            }

            if (allSupportsCamera2) {
                var defaultToCamera2 = false
                val isGoogle = Build.MANUFACTURER.lowercase().contains("google")
                val isNokia = Build.MANUFACTURER.lowercase().contains("hmd global")
                val isOneplus = Build.MANUFACTURER.lowercase().contains("oneplus")
                if (isGoogle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) defaultToCamera2 =
                    true
                else if (isNokia && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) defaultToCamera2 =
                    true
                else if (isSamsung && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) defaultToCamera2 =
                    true
                else if (isOneplus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) defaultToCamera2 =
                    true

                if (defaultToCamera2) {
                    if (MyDebug.LOG) Log.d(TAG, "default to camera2 API")
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                    sharedPreferences.edit {
                        putString(
                            PreferenceKeys.CAMERA_API_PREFERENCE_KEY,
                            "preference_camera_api_camera2"
                        )
                    }
                }
            }
        }
    }

    /** Switches modes if required, if called from a relevant intent/tile.
     */
    private fun setModeFromIntents(savedInstanceState: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "setModeFromIntents")
        if (savedInstanceState != null) {
            // If we're restoring from a saved state, we shouldn't be resetting any modes
            if (MyDebug.LOG) Log.d(TAG, "restoring from saved state")
            return
        }
        var doneFacing = false
        val action = this.intent.action
        if (MediaStore.INTENT_ACTION_VIDEO_CAMERA == action || MediaStore.ACTION_VIDEO_CAPTURE == action) {
            if (MyDebug.LOG) Log.d(TAG, "launching from video intent")
            applicationInterface.setVideoPref(true)
        } else if (MediaStore.ACTION_IMAGE_CAPTURE == action || MediaStore.ACTION_IMAGE_CAPTURE_SECURE == action || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA == action || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE == action) {
            if (MyDebug.LOG) Log.d(TAG, "launching from photo intent")
            applicationInterface.setVideoPref(false)
        } else if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MyTileService.TILE_ID == action) || ACTION_SHORTCUT_CAMERA == action
        ) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "launching from quick settings tile or application shortcut for Open Kamera: photo mode"
            )
            applicationInterface.setVideoPref(false)
        } else if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MyTileServiceVideo.TILE_ID == action) || ACTION_SHORTCUT_VIDEO == action
        ) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "launching from quick settings tile or application shortcut for Open Kamera: video mode"
            )
            applicationInterface.setVideoPref(true)
        } else if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MyTileServiceFrontCamera.TILE_ID == action) || ACTION_SHORTCUT_SELFIE == action
        ) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "launching from quick settings tile or application shortcut for Open Kamera: selfie mode"
            )
            doneFacing = true
            applicationInterface.switchToCamera(true)
        } else if (ACTION_SHORTCUT_GALLERY == action) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "launching from application shortcut for Open Kamera: gallery"
            )
            openGallery()
        } else if (ACTION_SHORTCUT_SETTINGS == action) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "launching from application shortcut for Open Kamera: settings"
            )
            openSettings()
        }

        val extras = this.intent.extras
        if (extras != null) {
            if (MyDebug.LOG) Log.d(TAG, "handle intent extra information")
            if (!doneFacing) {
                val cameraFacing = extras.getInt("android.intent.extras.CAMERA_FACING", -1)
                if (cameraFacing == 0 || cameraFacing == 1) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "found android.intent.extras.CAMERA_FACING: $cameraFacing"
                    )
                    applicationInterface.switchToCamera(cameraFacing == 1)
                    doneFacing = true
                }
            }
            if (!doneFacing) {
                if (extras.getInt("android.intent.extras.LENS_FACING_FRONT", -1) == 1) {
                    if (MyDebug.LOG) Log.d(TAG, "found android.intent.extras.LENS_FACING_FRONT")
                    applicationInterface.switchToCamera(true)
                    doneFacing = true
                }
            }
            if (!doneFacing) {
                if (extras.getInt("android.intent.extras.LENS_FACING_BACK", -1) == 1) {
                    if (MyDebug.LOG) Log.d(TAG, "found android.intent.extras.LENS_FACING_BACK")
                    applicationInterface.switchToCamera(false)
                    doneFacing = true
                }
            }
            if (!doneFacing) {
                if (extras.getBoolean("android.intent.extra.USE_FRONT_CAMERA", false)) {
                    if (MyDebug.LOG) Log.d(TAG, "found android.intent.extra.USE_FRONT_CAMERA")
                    applicationInterface.switchToCamera(true)
                    doneFacing = true
                }
            }
        }

        // N.B., in practice the hasSetCameraId() check is pointless as we don't save the camera ID in shared preferences, so it will always
        // be false when application is started from onCreate(), unless resuming from saved instance (in which case we shouldn't be here anyway)
        if (!doneFacing && !applicationInterface.hasSetCameraId()) {
            if (MyDebug.LOG) Log.d(TAG, "initialise to back camera")
            // most devices have first camera as back camera anyway so this wouldn't be needed, but some (e.g., LG G6) have first camera
            // as front camera, so we should explicitly switch to back camera
            applicationInterface.switchToCamera(false)
        }
    }

    /** Determine whether we support Camera2 API.
     */
    private fun initCamera2Support() {
        if (MyDebug.LOG) Log.d(TAG, "initCamera2Support")
        supportsCamera2 = false
        run {
            // originally we allowed Camera2 if all cameras support at least LIMITED
            // as of 1.45, we allow Camera2 if at least one camera supports at least LIMITED - this
            // is to support devices that might have a camera with LIMITED or better support, but
            // also a LEGACY camera
            val manager2: CameraControllerManager2 = CameraControllerManager2(this)
            supportsCamera2 = false
            val nCameras: Int = manager2.numberOfCameras
            if (nCameras == 0) {
                if (MyDebug.LOG) Log.d(TAG, "Camera2 reports 0 cameras")
                supportsCamera2 = false
            }
            var i = 0
            while (i < nCameras && !supportsCamera2) {
                if (manager2.allowCamera2Support(i)) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "camera $i has at least limited support for Camera2 API"
                    )
                    supportsCamera2 = true
                }
                i++
            }
        }

        //testForceSupportsCamera2 = true // test
        if (testForceSupportsCamera2) {
            if (MyDebug.LOG) Log.d(TAG, "forcing supports_camera2")
            supportsCamera2 = true
        }

        if (MyDebug.LOG) Log.d(
            TAG,
            "supports_camera2? $supportsCamera2"
        )

        // handle the switch from a boolean preferenceUseCamera2 to String preferenceCameraApi
        // that occurred in v1.48
        if (supportsCamera2) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            if (!sharedPreferences.contains(PreferenceKeys.CAMERA_API_PREFERENCE_KEY) // doesn't have the new key set yet
                && sharedPreferences.contains("preference_use_camera2") // has the old key set
                && sharedPreferences.getBoolean(
                    "preference_use_camera2",
                    false
                ) // and camera2 was enabled
            ) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "transfer legacy camera2 boolean preference to new api option"
                )
                sharedPreferences.edit {
                    putString(
                        PreferenceKeys.CAMERA_API_PREFERENCE_KEY,
                        "preference_camera_api_camera2"
                    )
                    remove("preference_use_camera2") // remove the old key, just in case
                }
            }
        }
    }

    /** Handles users updating to a version with scoped storage (this could be Android 10 users upgrading
     * to the version of Open Kamera with scoped storage or users who later upgrade to Android 10).
     * With scoped storage, we no longer support saving outside of DCIM/ when not using SAF.
     * This updates if necessary both the current save location, and the save folder history.
     */
    private fun checkSaveLocations() {
        if (MyDebug.LOG) Log.d(TAG, "checkSaveLocations")
        if (useScopedStorage()) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            var anyChanges = false
            val saveLocation: String = storageUtils.saveLocation
            var res: CheckSaveLocationResult =
                checkSaveLocation(saveLocation)
            if (!res.res) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "save_location not valid with scoped storage: $saveLocation"
                )
                val newFolder: String
                if (res.alt == null) {
                    // no alternative, fall back to default
                    newFolder = "OpenKamera"
                } else {
                    // replace with the alternative
                    if (MyDebug.LOG) Log.d(TAG, "alternative: " + res.alt)
                    newFolder = res.alt
                }
                sharedPreferences.edit {
                    putString(PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY, newFolder)
                }
                anyChanges = true
            }

            // now check history
            // go backwards so we can remove easily
            for (i in saveLocationHistory.size() - 1 downTo 0) {
                val thisLocation: String = saveLocationHistory[i]
                res = checkSaveLocation(thisLocation)
                if (!res.res) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "save_location in history $i not valid with scoped storage: $thisLocation"
                    )
                    if (res.alt == null) {
                        // no alternative, remove
                        saveLocationHistory.remove(i)
                    } else {
                        // replace with the alternative
                        if (MyDebug.LOG) Log.d(TAG, "alternative: " + res.alt)
                        saveLocationHistory[i] = res.alt
                    }
                    anyChanges = true
                }
            }

            if (anyChanges) {
                saveLocationHistory.updateFolderHistory(storageUtils.saveLocation, false)
            }
        }
    }

    /** Result from checkSaveLocation. Ideally we'd just use android.util.Pair, but that's not mocked
     * for use in unit tests.
     * See checkSaveLocation() for documentation.
     */
    class CheckSaveLocationResult(val res: Boolean, val alt: String?) {
        override fun equals(o: Any?): Boolean {
            if (o !is CheckSaveLocationResult) {
                return false
            }
            val that: CheckSaveLocationResult = o
            // stop dumb inspection that suggests replacing warning with an error(!) (Objects class is not available on all API versions)
            // and the other inspection suggests replacing with code that would cause a nullpointerexception
            return that.res == this.res && ((that.alt === this.alt) || (that.alt != null && that.alt == this.alt))
            //return that.res == this.res && ( (that.alt == this.alt) || (that.alt != null && that.alt.equals(this.alt) ) )
        }

        override fun hashCode(): Int {
            return (if (res) 1249 else 1259) xor (alt?.hashCode() ?: 0)
        }

        override fun toString(): String {
            return "CheckSaveLocationResult{$res , $alt}"
        }
    }

    private fun preloadIcons(iconsId: Int) {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "preloadIcons: $iconsId")
            debugTime = System.currentTimeMillis()
        }
        val icons = resources.obtainTypedArray(iconsId)
        try {
            for (i in 0 until icons.length()) {
                val resource = icons.getResourceId(i, 0)
                if (MyDebug.LOG) Log.d(TAG, "load resource: $resource")
                val bm = BitmapFactory.decodeResource(resources, resource)
                if (bm != null) {
                    preloadedBitmapResources[resource] = bm
                }
            }
        } finally {
            icons.recycle()
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "preloadIcons: total time for preloadIcons: " + (System.currentTimeMillis() - debugTime)
            )
            Log.d(TAG, "size of preloaded_bitmap_resources: " + preloadedBitmapResources.size)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStop() {
        if (MyDebug.LOG) Log.d(TAG, "onStop")
        super.onStop()

        // we stop location listening in onPause, but done here again just to be certain!
        applicationInterface.locationSupplier.freeLocationListeners()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onDestroy() {
        if (MyDebug.LOG) {
            Log.d(TAG, "on_destroy")
            Log.d(TAG, "size of preloaded_bitmap_resources: " + preloadedBitmapResources.size)
        }
        activityCount--
        if (MyDebug.LOG) Log.d(TAG, "activity_count: $activityCount")

        // should do asap before waiting for images to be saved - as risk the application will be killed whilst waiting for that to happen,
        // and we want to avoid notifications hanging around
        cancelImageSavingNotification()

        if (wantNoLimits && _navigationGap != 0) {
            if (MyDebug.LOG) Log.d(TAG, "clear FLAG_LAYOUT_NO_LIMITS")
            // it's unclear why this matters - but there is a bug when exiting split-screen mode, if the split-screen mode had set wantNoLimits:
            // even though the application is created when leaving split-screen mode, we still end up with the window flags for showing
            // under the navigation bar!
            // update: this issue is also fixed by not allowing wantNoLimits mode in multi-window mode, but still good to reset things here
            // just in case
            showUnderNavigation(false)
        }

        // reduce risk of losing any images
        // we don't do this in onPause or onStop, due to risk of ANRs
        // note that even if we did call this earlier in onPause or onStop, we'd still want to wait again here: as it can happen
        // that a new image appears after onPause/onStop is called, in which case we want to wait until images are saved,
        // otherwise we can have crash if we need Renderscript after calling releaseAllContexts(), or because rs has been set to
        // null from beneath applicationInterface.onDestroy()
        waitUntilImageQueueEmpty()

        preview.onDestroy()
        if (::applicationInterface.isInitialized) {
            applicationInterface.onDestroy()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activityCount == 0) {
            // See note in HDRProcessor.onDestroy() - but from Android M, renderscript contexts are released with releaseAllContexts()
            // doc for releaseAllContexts() says "If no contexts have been created this function does nothing"
            // Important to only do so if no other activities are running (see activityCount). Otherwise risk
            // of crashes if one activity is destroyed when another instance is still using Renderscript. I've
            // been unable to reproduce this, though such RSInvalidStateException crashes from Google Play.
            if (MyDebug.LOG) Log.d(TAG, "release renderscript contexts")
            RenderScript.releaseAllContexts()
        }
        // Need to recycle to avoid out of memory when running tests - probably good practice to do anyway
        for ((key, value) in preloadedBitmapResources) {
            if (MyDebug.LOG) Log.d(TAG, "recycle: $key")
            value.recycle()
        }
        preloadedBitmapResources.clear()
        if (textToSpeech != null) {
            // http://stackoverflow.com/questions/4242401/tts-error-leaked-serviceconnection-android-speech-tts-texttospeech-solved
            if (MyDebug.LOG) Log.d(TAG, "free textToSpeech")
            textToSpeech!!.stop()
            textToSpeech!!.shutdown()
            textToSpeech = null
        }

        // we stop location listening in onPause, but done here again just to be certain!
        applicationInterface.locationSupplier.freeLocationListeners()

        super.onDestroy()
        if (MyDebug.LOG) Log.d(TAG, "onDestroy done")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    private fun setFirstTimeFlag() {
        if (MyDebug.LOG) Log.d(TAG, "setFirstTimeFlag")
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.edit {
            putBoolean(PreferenceKeys.FIRST_TIME_PREFERENCE_KEY, true)
        }
    }

    fun launchOnlineHelp() {
        if (MyDebug.LOG) Log.d(TAG, "launchOnlineHelp")
        // if we change this, remember that any page linked to must abide by Google Play developer policies!
        val browserIntent = Intent(Intent.ACTION_VIEW, getOnlineHelpUrl("").toUri())
        startActivity(browserIntent)
    }

    fun launchOnlinePrivacyPolicy() {
        if (MyDebug.LOG) Log.d(TAG, "launchOnlinePrivacyPolicy")
        // if we change this, remember that any page linked to must abide by Google Play developer policies!
        //Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getOnlineHelpUrl("index.html#privacy")))
        val browserIntent =
            Intent(Intent.ACTION_VIEW, getOnlineHelpUrl("privacy_oc.html").toUri())
        startActivity(browserIntent)
    }

    fun launchOnlineLicences() {
        if (MyDebug.LOG) Log.d(TAG, "launchOnlineLicences")
        // if we change this, remember that any page linked to must abide by Google Play developer policies!
        val browserIntent = Intent(Intent.ACTION_VIEW, getOnlineHelpUrl("#licence").toUri())
        startActivity(browserIntent)
    }

    /* Audio trigger - either loud sound, or speech recognition.
     * This performs some additional checks before taking a photo.
     */
    fun audioTrigger() {
        if (MyDebug.LOG) Log.d(TAG, "ignore audio trigger due to popup open")
        if (popupIsOpen()) {
            if (MyDebug.LOG) Log.d(TAG, "ignore audio trigger due to popup open")
        } else if (isCameraInBackground) {
            if (MyDebug.LOG) Log.d(TAG, "ignore audio trigger due to camera in background")
        } else if (preview.isTakingPhotoOrOnTimer) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "ignore audio trigger due to already taking photo or on timer"
            )
        } else if (preview.isVideoRecording) {
            if (MyDebug.LOG) Log.d(TAG, "ignore audio trigger due to already recording video")
        } else {
            if (MyDebug.LOG) Log.d(TAG, "schedule take picture due to loud noise")
            //takePicture()
            this.runOnUiThread {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "taking picture due to audio trigger"
                )
                takePicture(false)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onKeyDown: $keyCode")
        if (isCameraInBackground) {
            // don't allow keys such as volume keys for taking photo when camera in background!
            if (MyDebug.LOG) Log.d(TAG, "camera is in background")
        } else {
            val handled: Boolean = mainUI.onKeyDown(keyCode, event)
            if (handled) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onKeyUp: $keyCode")
        if (isCameraInBackground) {
            // don't allow keys such as volume keys for taking photo when camera in background!
            if (MyDebug.LOG) Log.d(TAG, "camera is in background")
        } else {
            mainUI.onKeyUp(keyCode, event)
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun zoomByStep(change: Int) {
        var newChange = change
        if (MyDebug.LOG) Log.d(TAG, "zoomByStep: $newChange")
        if (preview.supportsZoom() && newChange != 0) {
            if (preview.cameraController != null) {
                // If the minimum zoom is < 1.0, the seekbar will have repeated entries for 1x zoom
                // (so it's easier for the user to zoom to exactly 1.0x). But if using the -/+ buttons,
                // volume keys etc to zoom, we want to skip over these repeated values.
                val zoomFactor: Int = preview.cameraController!!.zoom
                var newZoomFactor = zoomFactor + newChange
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "new_zoom_factor: $newZoomFactor"
                )
                while (newZoomFactor > 0 && newZoomFactor < preview.maxZoom && preview.getZoomRatio(
                        newZoomFactor
                    ) == preview.zoomRatio
                ) {
                    if (newChange > 0) newChange++
                    else newChange--
                    newZoomFactor = zoomFactor + newChange
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "skip over constant region: $newZoomFactor"
                    )
                }
            }

            mainUI.changeSeekbar(
                R.id.zoom_seekbar,
                -change
            ) // seekbar is opposite direction to zoom array
        }
    }

    fun zoomIn() {
        zoomByStep(1)
    }

    fun zoomOut() {
        zoomByStep(-1)
    }

    fun changeExposure(change: Int) {
        var newChange = change
        if (preview.supportsExposures()) {
            if (exposureSeekbarValues != null) {
                val seekBar = this.findViewById<SeekBar>(R.id.exposure_seekbar)
                val progress = seekBar.progress
                var newProgress = progress + newChange
                val currentExposure = getExposureSeekbarValue(progress)
                if (newProgress < 0 || newProgress > exposureSeekbarValues!!.size - 1) {
                    // skip
                } else if (getExposureSeekbarValue(newProgress) == 0 && currentExposure != 0) {
                    // snap to the central repeated zero
                    newProgress = exposureSeekbarProgressZero
                    newChange = newProgress - progress
                } else {
                    // skip over the repeated zeroes
                    while (newProgress > 0 && newProgress < exposureSeekbarValues!!.size - 1 && getExposureSeekbarValue(
                            newProgress
                        ) == currentExposure
                    ) {
                        if (newChange > 0) newChange++
                        else newChange--
                        newProgress = progress + newChange
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "skip over constant region: $newProgress"
                        )
                    }
                }
            }
            mainUI.changeSeekbar(R.id.exposure_seekbar, newChange)
        }
    }

    /** Returns the exposure compensation corresponding to progress on the seekbar.
     * Caller is responsible for checking that progress is within valid range.
     */
    fun getExposureSeekbarValue(progress: Int): Int {
        return exposureSeekbarValues!![progress]
    }

    fun changeISO(change: Int) {
        if (preview.supportsISORange()) {
            mainUI.changeSeekbar(R.id.iso_seekbar, change)
        }
    }

    fun changeFocusDistance(change: Int, isTargetDistance: Boolean) {
        mainUI.changeSeekbar(
            if (isTargetDistance) R.id.focus_bracketing_target_seekbar else R.id.focus_seekbar,
            change
        )
    }

    private val accelerometerListener: SensorEventListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        }

        override fun onSensorChanged(event: SensorEvent) {
            preview.onAccelerometerSensorChanged(event)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onResume() {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "onResume")
            debugTime = System.currentTimeMillis()
        }
        super.onResume()
        this.isAppPaused = false // must be set before initLocation() at least

        // this is intentionally true, not false, as the uncovering happens in DrawPreview when we receive frames from the camera after it's opened
        // (this should already have been set from the call in onPause(), but we set it here again just in case)
        applicationInterface.drawPreview.setCoverPreview(true)

        applicationInterface.drawPreview
            .clearDimPreview() // shouldn't be needed, but just in case the dim preview flag got set somewhere

        cancelImageSavingNotification()

        // Set black window background also needed if we hide the virtual buttons in immersive mode
        // Note that we do it here rather than customising the theme's android:windowBackground, so this doesn't affect other views - in particular, the MyPreferenceFragment settings
        window.decorView.rootView.setBackgroundColor(Color.BLACK)

        if (edgeToEdgeMode) {
            // needed on Android 15, otherwise the navigation bar is not transparent
            window.isNavigationBarContrastEnforced = false
        }

        registerDisplayListener()

        mSensorManager.registerListener(
            accelerometerListener,
            mSensorAccelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        magneticSensor.registerMagneticListener(mSensorManager)
        if (::orientationEventListener.isInitialized) {
            orientationEventListener.enable()
        }
        window.decorView.addOnLayoutChangeListener(layoutChangeListener)

        // if BLE remote control is enabled, then start the background BLE service
        bluetoothRemoteControl.startRemoteControl()

        //speechControl.initSpeechRecognizer()
        initLocation()
        initGyroSensors()
        applicationInterface.imageSaver.onResume()
        soundPoolManager.initSound()
        soundPoolManager.loadSound(R.raw.mybeep)
        soundPoolManager.loadSound(R.raw.mybeep_hi)

        resetCachedSystemOrientation() // just in case?
        mainUI.layoutUI()

        // If the cached last media has exif datetime info, it's fine to just call updateGalleryIcon(),
        // which will find the most recent media (and takes care of if the cached last image may have
        // been deleted).
        // If it doesn't have exif datetime tags, updateGalleryIcon() may not be able to find the most
        // recent media, so we stick with the cached uri if we can test that it's still accessible.
        if (!storageUtils.lastMediaScannedHasNoExifDateTime) {
            updateGalleryIcon()
        } else {
            if (MyDebug.LOG) Log.d(TAG, "last media has no exif datetime, so check it still exists")
            var uriExists = false
            var inputStream: InputStream? = null
            val checkUri: Uri? = storageUtils.lastMediaScannedCheckUri
            if (MyDebug.LOG) Log.d(TAG, "check_uri: $checkUri")
            try {
                inputStream = checkUri?.let { this.contentResolver.openInputStream(it) }
                if (inputStream != null) uriExists = true
            } catch (ignored: Exception) {
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }

            if (uriExists) {
                if (MyDebug.LOG) Log.d(TAG, "    most recent uri exists")
                // also re-allow ghost image again in case that option is set (since we won't be
                // doing this via updateGalleryIcon())
                applicationInterface.drawPreview.allowGhostImage()
            } else {
                if (MyDebug.LOG) Log.d(TAG, "    most recent uri no longer valid")
                updateGalleryIcon()
            }
        }

        applicationInterface.reset(false) // should be called before opening the camera in preview.onResume()

        if (!isCameraInBackground) {
            // don't restart camera if we're showing a dialog or settings
            preview.onResume()
        }

        run {
            // show a toast for the camera if it's not the first for front of back facing (otherwise on multi-front/back camera
            // devices, it's easy to forget if set to a different camera)
            // but we only show this when resuming, not every time the camera opens
            // OR show the toast for the camera if it's a physical camera
            val cameraId: Int = applicationInterface.getCameraIdPref()
            val cameraIdSPhysical: String? = applicationInterface.getCameraIdSPhysicalPref()
            if (cameraId > 0 || cameraIdSPhysical != null) {
                val cameraControllerManager = preview.cameraControllerManager
                val frontFacing = cameraControllerManager.getFacing(cameraId)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "front_facing: $frontFacing"
                )
                if ((cameraControllerManager.numberOfCameras ?: 0) > 2 || cameraIdSPhysical != null
                ) {
                    var cameraIsDefault = true
                    if (cameraIdSPhysical != null) cameraIsDefault = false
                    var i = 0
                    while (i < cameraId && cameraIsDefault) {
                        val thatFrontFacing: Facing? = cameraControllerManager?.getFacing(i)
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "camera $i that_front_facing: $thatFrontFacing"
                        )
                        if (thatFrontFacing === frontFacing) {
                            // found an earlier camera with same front/back facing
                            cameraIsDefault = false
                        }
                        i++
                    }
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "camera_is_default: $cameraIsDefault"
                    )
                    if (!cameraIsDefault) {
                        this.pushCameraIdToast(cameraId, cameraIdSPhysical)
                    }
                }
            }
        }

        pushSwitchedCamera = false // just in case

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "onResume: total time to resume: " + (System.currentTimeMillis() - debugTime)
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onWindowFocusChanged: $hasFocus"
        )
        super.onWindowFocusChanged(hasFocus)
        if (!this.isCameraInBackground && hasFocus) {
            // low profile mode is cleared when app goes into background
            // and for Kit Kat immersive mode, we want to set up the timer
            // we do in onWindowFocusChanged rather than onResume(), to also catch when window lost focus due to notification bar being dragged down (which prevents resetting of immersive mode)
            initImmersiveMode()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onPause() {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "onPause")
            debugTime = System.currentTimeMillis()
        }
        super.onPause() // docs say to call this before freeing other things
        this.isAppPaused = true

        mainUI.destroyPopup() // important as user could change/reset settings from Android settings when pausing
        unregisterDisplayListener()
        mSensorManager.unregisterListener(accelerometerListener)
        magneticSensor.unregisterMagneticListener(mSensorManager)
        if (::orientationEventListener.isInitialized) {
            orientationEventListener.disable()
        }
        window.decorView.removeOnLayoutChangeListener(layoutChangeListener)
        bluetoothRemoteControl.stopRemoteControl()
        freeAudioListener(false)
        //speechControl.stopSpeechRecognizer()
        applicationInterface.locationSupplier.freeLocationListeners()
        applicationInterface.stopPanorama(true) // in practice not needed as we should stop panorama when camera is closed, but good to do it explicitly here, before disabling the gyro sensors
        applicationInterface.gyroSensor.disableSensors()
        applicationInterface.imageSaver.onPause()
        soundPoolManager.releaseSound()
        applicationInterface.clearLastImages() // this should happen when pausing the preview, but call explicitly just to be safe
        applicationInterface.drawPreview.clearGhostImage()
        preview.onPause()
        applicationInterface.drawPreview
            .setCoverPreview(true) // must be after we've closed the preview (otherwise risk that further frames from preview will unset the coverPreview flag in DrawPreview)

        if (applicationInterface.imageSaver.nImagesToSave > 0) {
            createImageSavingNotification()
        }

        if (updateGalleryFuture != null) {
            updateGalleryFuture!!.cancel(true)
        }

        // intentionally do this again, just in case something turned location on since - keep this right at the end:
        applicationInterface.locationSupplier.freeLocationListeners()

        // don't want to enter immersive mode when in background
        // needs to be last in case anything above indirectly called initImmersiveMode()
        cancelImmersiveTimer()

        if (MyDebug.LOG) {
            Log.d(TAG, "onPause: total time to pause: " + (System.currentTimeMillis() - debugTime))
        }
    }

    private inner class MyDisplayListener : DisplayListener {
        private var oldRotation: Int

        init {
            val rotation = this@MainActivity.windowManager.defaultDisplay.rotation
            if (MyDebug.LOG) {
                Log.d(TAG, "MyDisplayListener")
                Log.d(TAG, "rotation: $rotation")
            }
            oldRotation = rotation
        }

        override fun onDisplayAdded(displayId: Int) {
        }

        override fun onDisplayRemoved(displayId: Int) {
        }

        override fun onDisplayChanged(displayId: Int) {
            val rotation = this@MainActivity.windowManager.defaultDisplay.rotation
            if (MyDebug.LOG) {
                Log.d(TAG, "onDisplayChanged: $displayId")
                Log.d(TAG, "rotation: $rotation")
                Log.d(TAG, "old_rotation: $oldRotation")
            }
            if ((rotation == Surface.ROTATION_0 && oldRotation == Surface.ROTATION_180) ||
                (rotation == Surface.ROTATION_180 && oldRotation == Surface.ROTATION_0) ||
                (rotation == Surface.ROTATION_90 && oldRotation == Surface.ROTATION_270) ||
                (rotation == Surface.ROTATION_270 && oldRotation == Surface.ROTATION_90)
            ) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "onDisplayChanged: switched between landscape and reverse orientation"
                )
                onSystemOrientationChanged()
            }

            oldRotation = rotation
        }
    }

    /** Creates and registers a display listener, needed to handle switches between landscape and
     * reverse landscape (without going via portrait) when lockToLandscape==false.
     */
    private fun registerDisplayListener() {
        if (MyDebug.LOG) Log.d(TAG, "registerDisplayListener")
        if (!LOCK_TO_LANDSCAPE) {
            displayListener = MyDisplayListener()
            val displayManager =
                getSystemService(DISPLAY_SERVICE) as DisplayManager
            displayManager.registerDisplayListener(displayListener, null)
        }
    }

    private fun unregisterDisplayListener() {
        if (MyDebug.LOG) Log.d(TAG, "unregisterDisplayListener")
        if (displayListener != null) {
            val displayManager =
                getSystemService(DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(displayListener)
            displayListener = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (MyDebug.LOG) Log.d(TAG, "onConfigurationChanged(): " + newConfig.orientation)
        // configuration change can include screen orientation (landscape/portrait) when not locked (when settings is open)
        // needed if app is paused/resumed when settings is open and device is in portrait mode
        // update: need this all the time when lockToLandscape==false
        onSystemOrientationChanged()
        super.onConfigurationChanged(newConfig)
    }

    private fun onSystemOrientationChanged() {
        if (MyDebug.LOG) Log.d(TAG, "onSystemOrientationChanged")

        // n.b., need to call this first, before preview.setCameraDisplayOrientation(), since
        // preview.setCameraDisplayOrientation() will call getDisplayRotation() and we don't want
        // to be using the outdated cached value now that the rotation has changed!
        // update: no longer relevant, as preview.setCameraDisplayOrientation() now sets
        // preferLater to true to avoid using cached value. But might as well call it first anyway.
        resetCachedSystemOrientation()

        preview.setCameraDisplayOrientation()
        if (!LOCK_TO_LANDSCAPE) {
            val newSystemOrientation: SystemOrientation = systemOrientation
            if (hasOldSystemOrientation && oldSystemOrientation == newSystemOrientation) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "onSystemOrientationChanged: orientation hasn't changed"
                )
            } else {
                if (hasOldSystemOrientation) {
                    // handle rotation animation
                    var startRotation =
                        getRotationFromSystemOrientation(oldSystemOrientation) - getRotationFromSystemOrientation(
                            newSystemOrientation
                        )
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "start_rotation: $startRotation"
                    )
                    if (startRotation < -180) startRotation += 360
                    else if (startRotation > 180) startRotation -= 360
                    mainUI.layoutUIWithRotation(startRotation.toFloat())
                } else {
                    mainUI.layoutUI()
                }
                applicationInterface.drawPreview.updateSettings()

                hasOldSystemOrientation = true
                oldSystemOrientation = newSystemOrientation
            }
        }
    }

    val systemOrientation: SystemOrientation
        /** Returns the current system orientation.
         * Note if lockToLandscape is true, this always returns LANDSCAPE even if called when we're
         * allowing configuration changes (e.g., in Settings or a dialog is showing). (This method,
         * and hence calls to it, were added to support lockToLandscape==false behaviour, and we
         * want to avoid changing behaviour for lockToLandscape==true behaviour.)
         * Note that this also caches the orientation: firstly for performance (as this is called from
         * DrawPreview), secondly to support REVERSE_LANDSCAPE, we don't want a sudden change if
         * getDefaultDisplay().getRotation() changes after the configuration changes.
         */
        get() {
            if (testForceSystemOrientation) {
                return testSystemOrientation
            }
            if (LOCK_TO_LANDSCAPE) {
                return SystemOrientation.LANDSCAPE
            }
            if (hasCachedSystemOrientation) {
                return cachedSystemOrientation
            }
            var result: SystemOrientation
            val systemOrientation = resources.configuration.orientation
            if (MyDebug.LOG) Log.d(
                TAG,
                "system orientation: $systemOrientation"
            )
            when (systemOrientation) {
                Configuration.ORIENTATION_LANDSCAPE -> {
                    result = SystemOrientation.LANDSCAPE
                    run {
                        val rotation = windowManager.defaultDisplay.rotation
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "rotation: $rotation"
                        )
                        when (rotation) {
                            Surface.ROTATION_0, Surface.ROTATION_90 ->                             // landscape
                                if (MyDebug.LOG) Log.d(TAG, "landscape")

                            Surface.ROTATION_180, Surface.ROTATION_270 -> {
                                // reverse landscape
                                if (MyDebug.LOG) Log.d(TAG, "reverse landscape")
                                result =
                                    SystemOrientation.REVERSE_LANDSCAPE
                            }

                            else -> if (MyDebug.LOG) Log.e(
                                TAG,
                                "unknown rotation: $rotation"
                            )
                        }
                    }
                }

                Configuration.ORIENTATION_PORTRAIT -> result =
                    SystemOrientation.PORTRAIT

                Configuration.ORIENTATION_UNDEFINED -> {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "unknown system orientation: $systemOrientation"
                    )
                    result = SystemOrientation.LANDSCAPE
                }

                else -> {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "unknown system orientation: $systemOrientation"
                    )
                    result = SystemOrientation.LANDSCAPE
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "system orientation is now: $result"
            )
            this.hasCachedSystemOrientation = true
            this.cachedSystemOrientation = result
            return result
        }

    private fun resetCachedSystemOrientation() {
        this.hasCachedSystemOrientation = false
        this.hasCachedDisplayRotation = false
    }

    /** A wrapper for getWindowManager().getDefaultDisplay().getRotation(), except if
     * lockToLandscape==false && preferLater==false, this uses a cached value.
     */
    fun getDisplayRotation(preferLater: Boolean): Int {
        /*if( MyDebug.LOG ) {
            Log.d(TAG, "getDisplayRotationDegrees")
            Log.d(TAG, "preferLater: " + preferLater)
        }*/
        if (LOCK_TO_LANDSCAPE || preferLater) {
            return windowManager.defaultDisplay.rotation
        }
        // we cache to reduce effect of annoying problem where rotation changes shortly before the
        // configuration actually changes (several frames), so on-screen elements would briefly show
        // in wrong location when device rotates from/to portrait and landscape also not a bad idea
        // to cache for performance anyway, to avoid calling
        // getWindowManager().getDefaultDisplay().getRotation() every frame
        val timeMs = System.currentTimeMillis()
        if (hasCachedDisplayRotation && timeMs < cachedDisplayRotationTimeMs + 1000) {
            return cachedDisplayRotation
        }
        hasCachedDisplayRotation = true
        val rotation = windowManager.defaultDisplay.rotation
        cachedDisplayRotation = rotation
        cachedDisplayRotationTimeMs = timeMs
        return rotation
    }

    fun waitUntilImageQueueEmpty() {
        if (MyDebug.LOG) Log.d(TAG, "waitUntilImageQueueEmpty")
        applicationInterface.imageSaver.waitUntilDone()
    }

    /**
     * @return True if the long-click is handled, otherwise return false to indicate that regular
     * click should still be triggered when the user releases the touch.
     */
    private fun longClickedTakePhoto(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "longClickedTakePhoto")
        if (preview.isVideo) {
            // no long-click action for video mode
        } else if (supportsFastBurst()) {
            // need to check whether fast burst is supported (including for the current resolution),
            // in case we're in Standard photo mode
            val currentSize: CameraController.Size? = preview.currentPictureSize
            if (currentSize != null && currentSize.supportsBurst) {
                val photoMode: PhotoMode = applicationInterface.photoMode
                if (photoMode === PhotoMode.Standard &&
                    applicationInterface.isRawOnly(photoMode)
                ) {
                    if (MyDebug.LOG) Log.d(TAG, "fast burst not supported in RAW-only mode")
                    // in JPEG+RAW mode, a continuous fast burst will only produce JPEGs which is fine but in RAW only mode,
                    // no images at all would be saved! (Or we could switch to produce JPEGs anyway, but this seems misleading
                    // in RAW only mode.)
                } else if (photoMode === PhotoMode.Standard ||
                    photoMode === PhotoMode.FastBurst
                ) {
                    this.takePicturePressed(photoSnapshot = false, continuousFastBurst = true)
                    return true
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "fast burst not supported for this resolution")
            }
        } else {
            if (MyDebug.LOG) Log.d(TAG, "fast burst not supported")
        }
        // return false, so a regular click will still be triggered when the user releases the touch
        return false
    }

    fun clickedTakePhoto(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedTakePhoto")
        this.takePicture(false)
    }

    /** User has clicked button to take a photo snapshot whilst video recording.
     */
    fun clickedTakePhotoVideoSnapshot(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedTakePhotoVideoSnapshot")
        this.takePicture(true)
    }

    fun clickedPauseVideo(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedPauseVideo")
        pauseVideo()
    }

    fun pauseVideo() {
        if (MyDebug.LOG) Log.d(TAG, "pauseVideo")
        if (preview.isVideoRecording) { // just in case
            preview.pauseVideo()
            mainUI.setPauseVideoContentDescription()
        }
    }

    private fun useRemotePauseResumeForVideo(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val remoteVideoMode: String = sharedPreferences.getString(
            PreferenceKeys.REMOTE_VIDEO_MODE,
            "preference_remote_video_mode_standard"
        )!!
        return "preference_remote_video_mode_pause" == remoteVideoMode
    }

    /** Action for Bluetooth remote control input (BluetoothRemoteControl).
     */
    fun triggerRemoteControlAction() {
        if (MyDebug.LOG) Log.d(TAG, "triggerRemoteControlAction")
        if (preview.isVideo && preview.isVideoRecording && useRemotePauseResumeForVideo()) {
            pauseVideo()
            return
        }

        takePicture(false)
    }

    fun clickedCancelPanorama(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedCancelPanorama")
        applicationInterface.stopPanorama(true)
    }

    fun clickedCycleRaw(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedCycleRaw")
        this.mainUI.getOnScreenIcons().clickedCycleRaw()
    }

    fun clickedStoreLocation(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedStoreLocation")
        this.mainUI.getOnScreenIcons().clickedStoreLocation()
    }

    fun clickedTextStamp(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedTextStamp")
        this.mainUI.getOnScreenIcons().clickedTextStamp()
    }

    fun clickedStamp(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedStamp")
        this.mainUI.getOnScreenIcons().clickedStamp()
    }

    fun clickedFocusPeaking(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedFocusPeaking")
        this.mainUI.getOnScreenIcons().clickedFocusPeaking()
    }

    fun clickedAutoLevel(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedAutoLevel")
        this.mainUI.getOnScreenIcons().clickedAutoLevel()
    }

    fun clickedCycleFlash(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedCycleFlash")
        cameraViewModel.onEvent(CameraUiEvent.OnFlashModeToggleClicked)
        this.mainUI.getOnScreenIcons().clickedCycleFlash()
    }

    fun clickedFaceDetection(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedFaceDetection")
        this.mainUI.getOnScreenIcons().clickedFaceDetection()
    }

    fun clickedAudioControl(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedAudioControl")
        this.mainUI.getOnScreenIcons().clickedAudioControl()
    }

    fun clickedCycleLockOrientation(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedCycleLockOrientation")
        this.mainUI.getOnScreenIcons().clickedCycleLockOrientation()
    }

    fun clickedPreviewShots(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedPreviewShots")
        this.mainUI.getOnScreenIcons().clickedPreviewShots()
    }

    val nextCameraId: Int
        /* Returns the cameraId that the "Switch camera" button will switch to.
                  * Note that this may not necessarily be the next camera ID, on multi camera devices (if
                  * isMultiCamEnabled() returns true).
                  */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getNextCameraId")
            var cameraId = actualCameraId
            if (MyDebug.LOG) Log.d(
                TAG,
                "current cameraId: $cameraId"
            )
            if (preview.canSwitchCamera()) {
                if (isMultiCamEnabled) {
                    // don't use preview.cameraController, as it may be null if user quickly switches between cameras
                    when (preview.cameraControllerManager.getFacing(cameraId)) {
                        Facing.FACING_BACK -> if (frontCameraIds.isNotEmpty()) cameraId =
                            frontCameraIds[0]
                        else if (otherCameraIds.isNotEmpty()) cameraId = otherCameraIds[0]

                        Facing.FACING_FRONT -> if (otherCameraIds.isNotEmpty()) cameraId =
                            otherCameraIds[0]
                        else if (backCameraIds.isNotEmpty()) cameraId = backCameraIds[0]

                        else -> if (backCameraIds.isNotEmpty()) cameraId = backCameraIds[0]
                        else if (frontCameraIds.isNotEmpty()) cameraId = frontCameraIds[0]
                    }
                } else {
                    val nCameras: Int = preview.cameraControllerManager.numberOfCameras
                    cameraId = (cameraId + 1) % nCameras
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "next cameraId: $cameraId"
            )
            return cameraId
        }

    /* Returns the next cameraId with the same-facing as current camera.
     * Should only be called if isMultiCamEnabled() returns true.
     * Only used for testing, now that we bring up a menu instead of cycling.
     */
    /*public int testGetNextMultiCameraId() {
        if( MyDebug.LOG )
            Log.d(TAG, "testGetNextMultiCameraId")
        if( !isMultiCamEnabled() ) {
            Log.e(TAG, "testGetNextMultiCameraId() called but not in multi-cam mode")
            throw new RuntimeException("testGetNextMultiCameraId() called but not in multi-cam mode")
        }
        List<Integer> cameraSet
        // don't use preview.cameraController, as it may be null if user quickly switches between cameras
        int currCameraId = getActualCameraId()
        switch( preview.cameraControllerManager.getFacing(currCameraId) ) {
            case FACING_BACK:
                cameraSet = backCameraIds
                break
            case FACING_FRONT:
                cameraSet = frontCameraIds
                break
            default:
                cameraSet = otherCameraIds
                break
        }
        int cameraId
        int indx = camera_set.indexOf(currCameraId)
        if( indx == -1 ) {
            Log.e(TAG, "camera id not in current camera set")
            // this shouldn't happen, but if it does, revert to the first camera id in the set
            // update: oddly had reports of IndexOutOfBoundsException crashes from Google Play from camera_set.get(0)
            // because of cameraSet having length 0, so stick with currCameraId in such cases
            if( camera_set.size() == 0 ) {
                Log.e(TAG, "cameraSet is empty")
                cameraId = currCameraId
            }
            else
                cameraId = camera_set.get(0)
        }
        else {
            indx = (indx+1) % camera_set.size()
            cameraId = camera_set.get(indx)
        }
        if( MyDebug.LOG )
            Log.d(TAG, "next multi cameraId: " + cameraId)
        return cameraId
    }*/
    private fun pushCameraIdToast(cameraId: Int, cameraIdSPhysical: String?) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "pushCameraIdToast: $cameraId"
        )
        if ((preview.cameraControllerManager.numberOfCameras) > 2 || cameraIdSPhysical != null) {
            // telling the user which camera is pointless for only two cameras, but on devices that now
            // expose many cameras it can be confusing, so show a toast to at least display the id
            // similarly we want to show a toast if using a physical camera, so user doesn't forget
            val description: String? =
                if (cameraIdSPhysical != null) preview.cameraControllerManager.getDescription(
                    info = null,
                    context = this,
                    cameraIdS = cameraIdSPhysical,
                    includeType = true,
                    includeAngles = true
                ) else preview.cameraControllerManager.getDescription(
                    this, cameraId
                )
            if (description != null) {
                var toastString = description
                if (cameraIdSPhysical == null)  // only add the ID if not a physical camera
                    toastString += ": " + resources.getString(R.string.camera_id) + " " + cameraId
                //preview.showToast(null, toastString)
                this.pushInfoToastText = toastString
            }
        }
    }

    fun userSwitchToCamera(cameraId: Int, cameraIdSPhysical: String?) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "userSwitchToCamera: $cameraId / $cameraIdSPhysical"
        )
        val switchCameraButton = findViewById<View>(R.id.switch_camera)
        val switchMultiCameraButton = findViewById<View>(R.id.switch_multi_camera)
        // prevent slowdown if user repeatedly clicks:
        switchCameraButton.isEnabled = false
        switchMultiCameraButton.isEnabled = false
        applicationInterface.reset(true)
        applicationInterface.drawPreview.setDimPreview(true)
        preview.setCamera(cameraId, cameraIdSPhysical)
        switchCameraButton.isEnabled = true
        switchMultiCameraButton.isEnabled = true
        // no need to call mainUI.setSwitchCameraContentDescription - this will be called from Preview.cameraSetup when the
        // new camera is opened
    }

    /**
     * Selects the next camera on the phone - in practice, switches between
     * front and back cameras
     */
    fun clickedSwitchCamera(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedSwitchCamera")
        cameraViewModel.onEvent(CameraUiEvent.OnSwitchCameraClicked)
        if (preview.isOpeningCamera) {
            if (MyDebug.LOG) Log.d(TAG, "already opening camera in background thread")
            return
        }
        this.closePopup()
        if (preview.canSwitchCamera()) {
            val cameraId = nextCameraId
            if (!isMultiCamEnabled) {
                pushCameraIdToast(cameraId, null)
            } else {
                // In multi-cam mode, no need to show the toast when just switching between front and back cameras.
                // But it is useful to clear an active fake toast, otherwise have issue if the user uses
                // clickedSwitchMultiCamera() (which displays a fake toast for the camera via the info toast), then
                // immediately uses clickedSwitchCamera() - the toast for the wrong camera will still be lingering
                // until it expires, which looks a bit strange.
                // (If using non-fake toasts, this isn't an issue, at least on Android 10+, as now toasts seem to
                // disappear when the user touches the screen anyway.)
                preview.clearActiveFakeToast()
            }
            userSwitchToCamera(cameraId, null)

            pushSwitchedCamera = true
        }
    }

    /** Returns list of logical cameras with same facing as the supplied cameraId.
     */
    fun getSameFacingLogicalCameras(cameraId: Int): List<Int> {
        val logicalCameraIds: MutableList<Int> = ArrayList()
        val thisFacing: Facing = preview.cameraControllerManager.getFacing(cameraId)
        for (i in 0..<preview.cameraControllerManager.numberOfCameras) {
            if (preview.cameraControllerManager.getFacing(i) !== thisFacing) {
                // only show cameras with same facing
                continue
            }
            logicalCameraIds.add(i)
        }
        return logicalCameraIds
    }

    /** User can long-click on switch multi cam icon to bring up a menu to switch to any camera.
     * Update: from v1.53 onwards with support for exposing physical lens, we always call this with
     * a regular click on the switch multi cam icon.
     */
    fun clickedSwitchMultiCamera(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedSwitchMultiCamera")

        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }
        //showPreview(false)
        val alertDialog = AlertDialog.Builder(this)
        alertDialog.setTitle(R.string.choose_camera)

        val currCameraId = actualCameraId
        val logicalCameraIds = getSameFacingLogicalCameras(currCameraId)
        if (MyDebug.LOG) Log.d(
            TAG,
            "clickedSwitchMultiCamera: time after logical_camera_ids: " + (System.currentTimeMillis() - debugTime)
        )

        val nLogicalCameras = logicalCameraIds.size
        var nCameras = nLogicalCameras
        if (preview.hasPhysicalCameras()) {
            nCameras += preview.physicalCameras!!.size
            //nCameras++ // for the info message
        }
        val items = arrayOfNulls<CharSequence>(nCameras)
        val itemsLogicalCameraId = IntArray(nCameras)
        val itemsPhysicalCameraId = arrayOfNulls<String>(nCameras)
        var index = 0
        var selected = -1
        val currPhysicalCameraId: String? = applicationInterface.getCameraIdSPhysicalPref()
        for (i in 0..<nLogicalCameras) {
            val logicalCameraId = logicalCameraIds[i]
            if (MyDebug.LOG) Log.d(
                TAG,
                "clickedSwitchMultiCamera: time before getDescription: " + (System.currentTimeMillis() - debugTime)
            )
            var cameraName =
                "$logicalCameraId: " + preview.cameraControllerManager.getDescription(
                    this, logicalCameraId
                )
            if (MyDebug.LOG) Log.d(
                TAG,
                "clickedSwitchMultiCamera: time after getDescription: " + (System.currentTimeMillis() - debugTime)
            )
            if (logicalCameraId == currCameraId) {
                // this is the current logical camera
                if (preview.hasPhysicalCameras()) {
                    cameraName += " (" + resources.getString(R.string.auto_lens) + ")"
                }
                if (currPhysicalCameraId == null) {
                    // the logical camera is being used directly
                    selected = index
                    //String htmlCameraName = "<b>[" + cameraName + "]</b>"
                    val htmlCameraName = "<b>$cameraName</b>"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        items[index] = Html.fromHtml(htmlCameraName, Html.FROM_HTML_MODE_LEGACY)
                    } else {
                        items[index] = Html.fromHtml(htmlCameraName)
                    }
                } else {
                    // a physical camera is in use, so don't bold this entry
                    items[index] = cameraName
                }
                itemsLogicalCameraId[index] = logicalCameraId
                itemsPhysicalCameraId[index] = null
                index++

                if (preview.hasPhysicalCameras()) {
                    // also add the physical cameras that underlie the current logical camera
                    val physicalCameraIds: Set<String> = preview.physicalCameras!!

                    // sort by view angle
                    class PhysicalCamera(val id: String) {
                        val description: String?
                        val viewAngle: SizeF?

                        init {
                            val info = CameraInfo()
                            this.description = preview.cameraControllerManager.getDescription(
                                info = info,
                                context = this@MainActivity,
                                cameraIdS = id, includeType = false, includeAngles = true
                            )
                            this.viewAngle = info.viewAngle
                        }
                    }

                    val physicalCameras = ArrayList<PhysicalCamera>()
                    for (physicalId in physicalCameraIds) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "clickedSwitchMultiCamera: time before getDescription: " + (System.currentTimeMillis() - debugTime)
                        )
                        physicalCameras.add(PhysicalCamera(physicalId))
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "clickedSwitchMultiCamera: time after getDescription: " + (System.currentTimeMillis() - debugTime)
                        )
                    }
                    run {
                        physicalCameras.sortWith { o1, o2 ->
                            if (o2.viewAngle == null || o1.viewAngle == null) return@sortWith -1
                            val diff = o2.viewAngle.width - o1.viewAngle.width
                            if (abs(diff.toDouble()) < 1.0e-5f) 0
                            else if (diff > 0.0f) 1
                            else -1
                        }
                    }

                    val indent = "&nbsp&nbsp&nbsp&nbsp"
                    for ((j, physicalCamera) in physicalCameras.withIndex()) {
                        val physicalId = physicalCamera.id
                        cameraName =
                            resources.getString(R.string.lens) + " " + j + ": " + physicalCamera.description
                        val htmlCameraName: String
                        if (currPhysicalCameraId != null && currPhysicalCameraId == physicalId) {
                            // this is the current physical camera
                            selected = index
                            //htmlCameraName = indent + "<b>[" + cameraName + "]</b>"
                            htmlCameraName = "$indent<b>$cameraName</b>"
                        } else {
                            htmlCameraName = indent + cameraName
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            items[index] =
                                Html.fromHtml(htmlCameraName, Html.FROM_HTML_MODE_LEGACY)
                        } else {
                            items[index] = Html.fromHtml(htmlCameraName)
                        }
                        itemsLogicalCameraId[index] = logicalCameraId
                        itemsPhysicalCameraId[index] = physicalId
                        index++

                    }
                }
            } else {
                items[index] = cameraName
                itemsLogicalCameraId[index] = logicalCameraId
                itemsPhysicalCameraId[index] = null
                index++
            }
        }
        /*if( preview.hasPhysicalCameras() ) {
            items[index] = getResources().getString(R.string.physical_cameras_info)
            itemsLogicalCameraId[index] = -1
            itemsPhysicalCameraId[index] = null
            //index++
        }*/
        if (MyDebug.LOG) Log.d(
            TAG,
            "clickedSwitchMultiCamera: time after building menu: " + (System.currentTimeMillis() - debugTime)
        )

        //alertDialog.setItems(items, new DialogInterface.OnClickListener() {
        alertDialog.setSingleChoiceItems(
            items, selected,
            DialogInterface.OnClickListener { dialog, which ->
                if (MyDebug.LOG) Log.d(TAG, "selected: $which")
                val logicalCamera = itemsLogicalCameraId[which]
                val physicalCamera = itemsPhysicalCameraId[which]
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "logical_camera: $logicalCamera"
                    )
                    Log.d(
                        TAG,
                        "physical_camera: $physicalCamera"
                    )
                }
                val nCameras: Int = preview.cameraControllerManager.numberOfCameras
                if (logicalCamera in 0..<nCameras) {
                    if (preview.isOpeningCamera) {
                        if (MyDebug.LOG) Log.d(TAG, "already opening camera in background thread")
                        return@OnClickListener
                    }
                    this@MainActivity.closePopup()
                    if (preview.canSwitchCamera()) {
                        pushCameraIdToast(logicalCamera, physicalCamera)
                        userSwitchToCamera(logicalCamera, physicalCamera)
                    }
                }
                //setWindowFlagsForCamera()
                //showPreview(true)
                dialog.dismiss() // need to explicitly dismiss for setSingleChoiceItems
            })
        /*alertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface arg0) {
                setWindowFlagsForCamera()
                showPreview(true)
            }
        })*/
        //setWindowFlagsForSettings(false) // set setLockProtect to false - no need to protect this dialog with lock screen (fine to run above lock screen if that option is set)
        //showAlert(alertDialog.create())
        val dialog = alertDialog.create()
        if (preview.hasPhysicalCameras()) {
            val footer = TextView(this)
            footer.setText(R.string.physical_cameras_info)
            val scale = resources.displayMetrics.density
            val padding = (5 * scale + 0.5f).toInt() // convert dps to pixels
            footer.setPadding(padding, padding, padding, padding)
            dialog.listView.addFooterView(footer, null, false)
        }
        if (dialog.window != null) {
            dialog.window!!.setWindowAnimations(R.style.DialogAnimation)
        }
        dialog.show()
        if (MyDebug.LOG) Log.d(
            TAG,
            "clickedSwitchMultiCamera: total time: " + (System.currentTimeMillis() - debugTime)
        )
    }

    /**
     * Toggles Photo/Video mode
     */
    fun clickedSwitchVideo(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedSwitchVideo")
        val newMode = if (preview.isVideo) CaptureMode.PHOTO else CaptureMode.VIDEO
        cameraViewModel.onEvent(CameraUiEvent.OnCaptureModeSelected(newMode))
        this.closePopup()
        mainUI.destroyPopup() // important as we don't want to use a cached popup, as we can show different options depending on whether we're in photo or video mode

        // In practice stopping the gyro sensor shouldn't be needed as (a) we don't show the switch
        // photo/video icon when recording, (b) at the time of writing switching to video mode
        // reopens the camera, which will stop panorama recording anyway, but we do this just to be
        // safe.
        applicationInterface.stopPanorama(true)

        val switchVideoButton = findViewById<View>(R.id.switch_video)
        switchVideoButton.isEnabled = false // prevent slowdown if user repeatedly clicks
        applicationInterface.reset(false)
        applicationInterface.drawPreview.setDimPreview(true)
        preview.switchVideo(duringStartup = false, changeUserPref = true)
        switchVideoButton.isEnabled = true

        mainUI.setTakePhotoIcon()
        mainUI.setPopupIcon() // needed as turning to video mode or back can turn flash mode off or back on

        // ensure icons invisible if they're affected by being in video mode or not (e.g., on-screen RAW icon)
        // (if enabling them, we'll make the icon visible later on)
        checkDisableGUIIcons()

        if (!blockStartupToast) {
            this.showPhotoVideoToast(true)
        }
    }

    fun clickedWhiteBalanceLock(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedWhiteBalanceLock")
        this.mainUI.getOnScreenIcons().clickedWhiteBalanceLock()
    }

    fun clickedExposureLock(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedExposureLock")
        this.mainUI.getOnScreenIcons().clickedExposureLock()
    }

    fun clickedExposure(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedExposure")
        mainUI.toggleExposureUI()
    }

    fun clickedSettings(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedSettings")
        KeyguardUtils.requireKeyguard(this) { this.openSettings() }
    }

    fun popupIsOpen(): Boolean {
        return mainUI.popupIsOpen()
    }

    // for testing
    fun getUIButton(key: String): View? {
        return mainUI.getUIButton(key)
    }

    fun closePopup() {
        mainUI.closePopup()
    }

    fun getPreloadedBitmap(resource: Int): Bitmap? {
        return preloadedBitmapResources[resource]
    }

    fun clickedPopupSettings(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedPopupSettings")
        mainUI.togglePopupSettings()
    }

    private val preferencesListener = PreferencesListener()

    /** Keeps track of changes to SharedPreferences.
     */
    internal inner class PreferencesListener : OnSharedPreferenceChangeListener {
        // whether any changes that require updateForSettings have been made since startListening()
        private var anySignificantChange = false

        // whether any changes have been made since startListening()
        private var anyChange = false
        private val TAG = "PreferencesListener"

        fun startListening() {
            if (MyDebug.LOG) Log.d(
                TAG,
                "startListening"
            )
            anySignificantChange = false
            anyChange = false

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            // n.b., registerOnSharedPreferenceChangeListener warns that we must keep a reference to the listener (which
            // is this class) as long as we want to listen for changes, otherwise the listener may be garbage collected!
            sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }

        fun stopListening() {
            if (MyDebug.LOG) Log.d(
                TAG,
                "stopListening"
            )
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "onSharedPreferenceChanged: $key"
            )

            if (key == null) {
                // on Android 11+, when targetting Android 11+, this method is called with key==null
                // if preferences are cleared (see testSettings(), or when doing "Reset settings")
                return
            }

            anyChange = true

            when (key) {
                "preference_timer", "preference_burst_mode", "preference_burst_interval", "preference_touch_capture", "preference_pause_preview", "preference_shutter_sound", "preference_timer_beep", "preference_timer_speak", "preference_volume_keys", "preference_audio_noise_control_sensitivity", "preference_lock_orientation", "preference_using_saf", "preference_save_photo_prefix", "preference_save_video_prefix", "preference_save_zulu_time", "preference_show_when_locked", "preference_startup_focus", "ghost_image_alpha", "preference_focus_assist", "preference_show_zoom", "preference_show_angle", "preference_show_angle_line", "preference_show_pitch_lines", "preference_angle_highlight_color", "preference_show_battery", "preference_show_time", "preference_free_memory", "preference_show_iso", "preference_histogram", "preference_zebra_stripes", "preference_zebra_stripes_foreground_color", "preference_zebra_stripes_background_color", "preference_focus_peaking", "preference_focus_peaking_color", "preference_show_video_max_amp", "preference_grid", "preference_crop_guide", "preference_thumbnail_animation", "preference_take_photo_border", "preference_show_toasts", "preference_show_whats_new", "preference_keep_display_on", "preference_max_brightness", "preference_hdr_tonemapping", "preference_hdr_contrast_enhancement", "preference_panorama_crop", "preference_front_camera_mirror", "preference_exif_artist", "preference_exif_copyright", "preference_stamp", "preference_stamp_dateformat", "preference_stamp_timeformat", "preference_stamp_gpsformat", "preference_stamp_geo_address", "preference_units_distance", "preference_textstamp", "preference_stamp_fontsize", "preference_stamp_font_color", "preference_stamp_style", "preference_background_photo_saving", "preference_record_audio", "preference_record_audio_src", "preference_record_audio_channels", "preference_lock_video", "preference_video_subtitle", "preference_video_low_power_check", "preference_video_flash", "preference_require_location" ->                     //case "preference_antibanding": // need to set up camera controller
                    //case "preference_edge_mode": // need to set up camera controller
                    //case "preference_noise_reduction_mode": // need to set up camera controller
                    //case "preference_camera_api": // no point whitelisting as we restart anyway
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "this change doesn't require update"
                    )

                PreferenceKeys.ENABLE_REMOTE -> bluetoothRemoteControl.startRemoteControl()
                PreferenceKeys.REMOTE_NAME -> {
                    // The remote address changed, restart the service
                    if (bluetoothRemoteControl.remoteEnabled()) bluetoothRemoteControl.stopRemoteControl()
                    bluetoothRemoteControl.startRemoteControl()
                }

                PreferenceKeys.WATER_TYPE -> {
                    val wt = sharedPreferences.getBoolean(PreferenceKeys.WATER_TYPE, true)
                    this@MainActivity.waterDensity =
                        if (wt) WATER_DENSITY_SALTWATER else WATER_DENSITY_FRESHWATER
                }

                else -> {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "this change does require update"
                    )
                    anySignificantChange = true
                }
            }
        }

        fun anyChange(): Boolean {
            return anyChange
        }

        fun anySignificantChange(): Boolean {
            return anySignificantChange
        }
    }

    fun openSettings() {
        if (MyDebug.LOG) Log.d(TAG, "openSettings")
        closePopup() // important to close the popup to avoid confusing with back button callbacks
        preview.cancelTimer() // best to cancel any timer, in case we take a photo while settings window is open, or when changing settings
        preview.cancelRepeat() // similarly cancel the auto-repeat mode!
        preview.stopVideo(false) // important to stop video, as we'll be changing camera parameters when the settings window closes
        applicationInterface.stopPanorama(true) // important to stop panorama recording, as we might end up as we'll be changing camera parameters when the settings window closes
        stopAudioListeners()
        // close back handler callbacks (so back button is enabled again when going to settings) - in theory shouldn't be needed as all of these should
        // be disabled now, but just in case:
        this.enablePopupOnBackPressedCallback(false)
        this.enablePausePreviewOnBackPressedCallback(false)
        this.enableScreenLockOnBackPressedCallback(false)

        val bundle = Bundle()
        bundle.putBoolean("edge_to_edge_mode", edgeToEdgeMode)
        bundle.putInt("cameraId", preview.cameraId)
        bundle.putString("cameraIdSPhysical", applicationInterface.getCameraIdSPhysicalPref())
        bundle.putInt("nCameras", preview.cameraControllerManager.numberOfCameras)
        bundle.putBoolean("camera_open", preview.cameraController != null)
        bundle.putString("camera_api", preview.cameraAPI)
        bundle.putBoolean("using_android_l", preview.usingCamera2API())
        if (preview.cameraController != null) {
            bundle.putInt(
                "camera_orientation",
                preview.cameraController!!.cameraOrientation
            )
        }
        bundle.putString(
            "photo_mode_string",
            getPhotoModeString(applicationInterface.photoMode, true)
        )
        bundle.putBoolean("supports_auto_stabilise", this.supportsAutoStabilise)
        bundle.putBoolean("supports_flash", preview.supportsFlash())
        bundle.putBoolean("supports_force_video_4k", this.supportsForceVideo4k)
        bundle.putBoolean("supports_camera2", this.supportsCamera2)
        bundle.putBoolean("supports_face_detection", preview.supportsFaceDetection())
        bundle.putBoolean("supports_jpeg_r", preview.supportsJpegR())
        bundle.putBoolean("supports_raw", preview.supportsRaw())
        bundle.putBoolean("supports_burst_raw", this.supportsBurstRaw())
        bundle.putBoolean("supports_optimise_focus_latency", this.supportsOptimiseFocusLatency())
        bundle.putBoolean("supports_preshots", this.supportsPreShots())
        bundle.putBoolean("supports_hdr", this.supportsHDR())
        bundle.putBoolean("supports_nr", this.supportsNoiseReduction())
        bundle.putBoolean("supports_panorama", this.supportsPanorama())
        bundle.putBoolean("has_gyro_sensors", applicationInterface.gyroSensor.hasSensors())
        bundle.putBoolean("supports_expo_bracketing", this.supportsExpoBracketing())
        bundle.putBoolean("supports_preview_bitmaps", this.supportsPreviewBitmaps())
        bundle.putInt("max_expo_bracketing_n_images", this.maxExpoBracketingNImages())
        bundle.putBoolean("supports_exposure_compensation", preview.supportsExposures())
        bundle.putInt("exposure_compensation_min", preview.minimumExposure)
        bundle.putInt("exposure_compensation_max", preview.maximumExposure)
        bundle.putBoolean("supports_iso_range", preview.supportsISORange())
        bundle.putInt("iso_range_min", preview.minimumISO)
        bundle.putInt("iso_range_max", preview.maximumISO)
        bundle.putBoolean("supports_exposure_time", preview.supportsExposureTime())
        bundle.putBoolean("supports_exposure_lock", preview.supportsExposureLock())
        bundle.putBoolean("supports_white_balance_lock", preview.supportsWhiteBalanceLock())
        bundle.putLong("exposure_time_min", preview.minimumExposureTime)
        bundle.putLong("exposure_time_max", preview.maximumExposureTime)
        bundle.putBoolean(
            "supports_white_balance_temperature",
            preview.supportsWhiteBalanceTemperature()
        )
        bundle.putInt("white_balance_temperature_min", preview.minimumWhiteBalanceTemperature)
        bundle.putInt("white_balance_temperature_max", preview.maximumWhiteBalanceTemperature)
        bundle.putBoolean("is_multi_cam", this.isMultiCam)
        bundle.putBoolean("has_physical_cameras", preview.hasPhysicalCameras())
        bundle.putBoolean("supports_optical_stabilization", preview.supportsOpticalStabilization())
        bundle.putBoolean("optical_stabilization_enabled", preview.opticalStabilization)
        bundle.putBoolean("supports_video_stabilization", preview.supportsVideoStabilization())
        bundle.putBoolean("video_stabilization_enabled", preview.videoStabilization)
        bundle.putBoolean("can_disable_shutter_sound", preview.canDisableShutterSound())
        bundle.putInt("tonemap_max_curve_points", preview.tonemapMaxCurvePoints)
        bundle.putBoolean("supports_tonemap_curve", preview.supportsTonemapCurve)
        bundle.putBoolean("supports_photo_video_recording", preview.supportsPhotoVideoRecording())
        bundle.putFloat("camera_view_angle_x", preview.getViewAngleX(false))
        bundle.putFloat("camera_view_angle_y", preview.getViewAngleY(false))
        bundle.putFloat("min_zoom_factor", preview.minZoomRatio)
        bundle.putFloat("max_zoom_factor", preview.maxZoomRatio)

        putBundleExtra(bundle, "color_effects", preview.supportedColorEffects)
        putBundleExtra(bundle, "scene_modes", preview.supportedSceneModes)
        putBundleExtra(bundle, "white_balances", preview.supportedWhiteBalances)
        putBundleExtra(bundle, "isos", preview.supportedISOs)
        bundle.putInt("magnetic_accuracy", magneticSensor.magneticAccuracy)
        bundle.putString("iso_key", preview.isoKey)
        if (preview.cameraController != null) {
            bundle.putString(
                "parameters_string",
                preview.cameraController!!.parametersString
            )
        }
        val antibanding: List<String>? = preview.supportedAntiBanding
        putBundleExtra(bundle, "antibanding", antibanding)
        if (antibanding != null) {
            val entriesArr = arrayOfNulls<String>(antibanding.size)
            for ((i, value) in antibanding.withIndex()) {
                entriesArr[i] = mainUI.getEntryForAntiBanding(value)
            }
            bundle.putStringArray("antibanding_entries", entriesArr)
        }
        val edgeModes: List<String>? = preview.supportedEdgeModes
        putBundleExtra(bundle, "edge_modes", edgeModes)
        if (edgeModes != null) {
            val entriesArr = arrayOfNulls<String>(edgeModes.size)
            for ((i, value) in edgeModes.withIndex()) {
                entriesArr[i] = mainUI.getEntryForNoiseReductionMode(value)
            }
            bundle.putStringArray("edge_modes_entries", entriesArr)
        }
        val noiseReductionModes: List<String>? = preview.supportedNoiseReductionModes
        putBundleExtra(bundle, "noise_reduction_modes", noiseReductionModes)
        if (noiseReductionModes != null) {
            val entriesArr = arrayOfNulls<String>(noiseReductionModes.size)
            for ((i, value) in noiseReductionModes.withIndex()) {
                entriesArr[i] = mainUI.getEntryForNoiseReductionMode(value)
            }
            bundle.putStringArray("noise_reduction_modes_entries", entriesArr)
        }

        val previewSizes: List<CameraController.Size>? = preview.supportedPreviewSizes
        if (previewSizes != null) {
            val widths = IntArray(previewSizes.size)
            val heights = IntArray(previewSizes.size)
            for ((i, size) in previewSizes.withIndex()) {
                widths[i] = size.width
                heights[i] = size.height
            }
            bundle.putIntArray("preview_widths", widths)
            bundle.putIntArray("preview_heights", heights)
        }
        bundle.putInt("preview_width", preview.currentPreviewSize.width)
        bundle.putInt("preview_height", preview.currentPreviewSize.height)

        // Note that we set checkBurst to false, as the Settings always displays all supported resolutions (along with the "saved"
        // resolution preference, even if that doesn't support burst, and we're in a burst mode).
        // This is to be consistent with other preferences, e.g., we still show RAW settings even though that might not be supported
        // for the current photo mode.
        val sizes: List<CameraController.Size> = preview.getSupportedPictureSizes(false)
        if (sizes.isNotEmpty()) {
            val widths = IntArray(sizes.size)
            val heights = IntArray(sizes.size)
            val supportsBurst = BooleanArray(sizes.size)
            for ((i, size) in sizes.withIndex()) {
                widths[i] = size.width
                heights[i] = size.height
                supportsBurst[i] = size.supportsBurst
            }
            bundle.putIntArray("resolution_widths", widths)
            bundle.putIntArray("resolution_heights", heights)
            bundle.putBooleanArray("resolution_supports_burst", supportsBurst)
        }
        if (preview.currentPictureSize != null) {
            bundle.putInt("resolution_width", preview.currentPictureSize!!.width)
            bundle.putInt("resolution_height", preview.currentPictureSize!!.height)
        }

        //List<String> videoQuality = this.preview.videoQualityHander.getSupportedVideoQuality()
        val fpsValue: String =
            applicationInterface.getVideoFPSPref() // n.b., this takes into account slow motion mode putting us into a high frame rate
        if (MyDebug.LOG) Log.d(TAG, "fps_value: $fpsValue")
        var videoQuality: List<String> = preview.getSupportedVideoQuality(fpsValue)
        if (videoQuality.isEmpty()) {
            Log.e(TAG, "can't find any supported video sizes for current fps!")
            // fall back to unfiltered list
            videoQuality = preview.videoQualityHander.supportedVideoQuality
        }
        if (videoQuality.isNotEmpty() && preview.cameraController != null) {
            val videoQualityArr = arrayOfNulls<String>(videoQuality.size)
            val videoQualityStringArr = arrayOfNulls<String>(videoQuality.size)
            for ((i, value) in videoQuality.withIndex()) {
                videoQualityArr[i] = value
                videoQualityStringArr[i] = preview.getCamcorderProfileDescription(value)
            }
            bundle.putStringArray("video_quality", videoQualityArr)
            bundle.putStringArray("video_quality_string", videoQualityStringArr)

            val isHighSpeed: Boolean = preview.fpsIsHighSpeed(fpsValue)
            bundle.putBoolean("video_is_high_speed", isHighSpeed)
            val videoQualityPreferenceKey: String = PreferenceKeys.getVideoQualityPreferenceKey(
                preview.cameraId,
                applicationInterface.getCameraIdSPhysicalPref(),
                isHighSpeed
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "video_quality_preference_key: $videoQualityPreferenceKey"
            )
            bundle.putString("video_quality_preference_key", videoQualityPreferenceKey)
        }

        if (preview.videoQualityHander.currentVideoQuality != null) {
            bundle.putString(
                "current_video_quality",
                preview.videoQualityHander.currentVideoQuality
            )
        }
        val camcorderProfile: VideoProfile = preview.videoProfile
        bundle.putInt("video_frame_width", camcorderProfile.videoFrameWidth)
        bundle.putInt("video_frame_height", camcorderProfile.videoFrameHeight)
        bundle.putInt("video_bit_rate", camcorderProfile.videoBitRate)
        bundle.putInt("video_frame_rate", camcorderProfile.videoFrameRate)
        bundle.putDouble("video_capture_rate", camcorderProfile.videoCaptureRate)
        bundle.putBoolean("video_high_speed", preview.isVideoHighSpeed)
        bundle.putFloat(
            "video_capture_rate_factor",
            applicationInterface.getVideoCaptureRateFactor()
        )

        val videoSizes: List<CameraController.Size> =
            preview.videoQualityHander.supportedVideoSizes
        if (videoSizes.isNotEmpty()) {
            val widths = IntArray(videoSizes.size)
            val heights = IntArray(videoSizes.size)
            for ((i, size) in videoSizes.withIndex()) {
                widths[i] = size.width
                heights[i] = size.height
            }
            bundle.putIntArray("video_widths", widths)
            bundle.putIntArray("video_heights", heights)
        }

        // set up supported fps values
        if (preview.usingCamera2API()) {
            // with Camera2, we know what frame rates are supported
            val candidateFps = intArrayOf(15, 24, 25, 30, 60, 96, 100, 120, 240)
            val videoFps: MutableList<Int> = ArrayList()
            val videoFpsHighSpeed: MutableList<Boolean> = ArrayList()
            for (fps in candidateFps) {
                if (preview.fpsIsHighSpeed(fps.toString())) {
                    videoFps.add(fps)
                    videoFpsHighSpeed.add(true)
                } else if (preview.videoQualityHander.videoSupportsFrameRate(fps)) {
                    videoFps.add(fps)
                    videoFpsHighSpeed.add(false)
                }
            }
            val videoFpsArray = IntArray(videoFps.size)
            for (i in videoFps.indices) {
                videoFpsArray[i] = videoFps[i]
            }
            bundle.putIntArray("video_fps", videoFpsArray)
            val videoFpsHighSpeedArray = BooleanArray(videoFpsHighSpeed.size)
            for (i in videoFpsHighSpeed.indices) {
                videoFpsHighSpeedArray[i] = videoFpsHighSpeed[i]
            }
            bundle.putBooleanArray("video_fps_high_speed", videoFpsHighSpeedArray)
        } else {
            // with old API, we don't know what frame rates are supported, so we make it up and let the user try
            // probably shouldn't allow 120fps, but we did in the past, and there may be some devices where this did work?
            val videoFps = intArrayOf(15, 24, 25, 30, 60, 96, 100, 120)
            bundle.putIntArray("video_fps", videoFps)
            val videoFpsHighSpeedArray = BooleanArray(videoFps.size)
            for (i in videoFps.indices) {
                videoFpsHighSpeedArray[i] =
                    false // no concept of high speed frame rates in old API
            }
            bundle.putBooleanArray("video_fps_high_speed", videoFpsHighSpeedArray)
        }

        putBundleExtra(bundle, "flash_values", preview.supportedFlashValues)
        putBundleExtra(bundle, "focus_values", preview.supportedFocusValues)

        preferencesListener.startListening()

        showPreview(false)
        setWindowFlagsForSettings() // important to do after passing camera info into bundle, since this will close the camera
        val fragment = MyPreferenceFragment()
        fragment.arguments = bundle
        // use commitAllowingStateLoss() instead of commit(), does to "java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState" crash seen on Google Play
        // see http://stackoverflow.com/questions/7575921/illegalstateexception-can-not-perform-this-action-after-onsaveinstancestate-wit
        fragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "PREFERENCE_FRAGMENT").addToBackStack(null)
            .commitAllowingStateLoss()
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragment, pref: Preference): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "onPreferenceStartFragment")
            Log.d(TAG, "pref: " + pref.fragment)
        }

        // instantiate the new fragment
        //final Bundle args = pref.getExtras()
        // we want to pass the caller preference fragment's bundle to the new sub-screen (this will be a
        // copy of the bundle originally created in openSettings()
        val args = Bundle(caller.arguments)

        val fragment = Fragment.instantiate(this, pref.fragment, args)
        fragment.setTargetFragment(caller, 0)
        if (MyDebug.LOG) Log.d(TAG, "replace fragment")
        /*getFragmentManager().beginTransaction()
                .replace(R.id.content, fragment)
                .addToBackStack(null)
                .commit()*/
        fragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "PREFERENCE_FRAGMENT_" + pref.fragment)
            .addToBackStack(null).commitAllowingStateLoss()

        /*
        // AndroidX version:
        final Fragment fragment = getSupportFragmentManager().getFragmentFactory().instantiate(
                getClassLoader(),
                pref.getFragment())
        fragment.setArguments(args)
        fragment.setTargetFragment(caller, 0)
        // replace the existing fragment with the new fragment:
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content, fragment)
                .addToBackStack(null)
                .commit()
         */
        return true
    }

    /** Must be called when a settings (as stored in SharedPreferences) are made, so we can update the
     * camera, and make any other necessary changes.
     * @param updateCamera Whether the camera needs to be updated. Can be set to false if we know changes
     * haven't been made to the camera settings, or we already reopened it.
     * @param toastMessage If non-null, display this toast instead of the usual camera "startup" toast
     * that's shown in showPhotoVideoToast(). If non-null but an empty string, then
     * this means no toast is shown at all.
     * @param keepPopup    If false, the popup will be closed and destroyed. Set to true if you're sure
     * that the changed setting isn't one that requires the PopupView to be recreated
     * @param allowDim     If true, for Camera2 API a dimming effect will be applied if updating the
     * camera.
     */
    @JvmOverloads
    fun updateForSettings(
        updateCamera: Boolean,
        toastMessage: String? = null,
        keepPopup: Boolean = false,
        allowDim: Boolean = false
    ) {
        if (MyDebug.LOG) {
            Log.d(TAG, "updateForSettings()")
            if (toastMessage != null) {
                Log.d(TAG, "toast_message: $toastMessage")
            }
        }
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }

        // make sure we're into continuous video mode
        // workaround for bug on Samsung Galaxy S5 with UHD, where if the user switches to another (non-continuous-video) focus mode, then goes to Settings, then returns and records video, the preview freezes and the video is corrupted
        // so to be safe, we always reset to continuous video mode, and then reset it afterward
        /*String savedFocusValue = preview.updateFocusForVideo() // n.b., may be null if focus mode not changed
		if( MyDebug.LOG )
			Log.d(TAG, "savedFocusValue: " + savedFocusValue)*/
        if (MyDebug.LOG) Log.d(TAG, "update folder history")
        saveLocationHistory.updateFolderHistory(
            storageUtils.saveLocation,
            true
        ) // this also updates the last icon for ghost image, if that pref has changed
        // no need to update saveLocationHistorySaf, as we always do this in onActivityResult()
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "updateForSettings: time after update folder history: " + (System.currentTimeMillis() - debugTime)
            )
        }

        imageQueueChanged() // needed at least for changing photo mode, but might as well call it always

        if (!keepPopup) {
            mainUI.destroyPopup() // important as we don't want to use a cached popup
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "updateForSettings: time after destroy popup: " + (System.currentTimeMillis() - debugTime)
                )
            }
        }

        // update camera for changes made in prefs - do this without closing and reopening the camera app if possible for speed!
        // but need workaround for Nexus 7 bug on old camera API, where scene mode doesn't take effect unless the camera is restarted - I can reproduce this with other 3rd party camera apps, so may be a Nexus 7 issue...
        // doesn't happen if we allow using Camera2 API on Nexus 7, but reopen for consistency (and changing scene modes via
        // popup menu no longer should be calling updateForSettings() for Camera2, anyway)
        var needReopen = false
        if (updateCamera && preview.cameraController != null) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            var sceneMode: String? = preview.cameraController!!.sceneMode
            if (MyDebug.LOG) Log.d(
                TAG,
                "scene mode was: $sceneMode"
            )
            val key: String = PreferenceKeys.SCENE_MODE_PREFERENCE_KEY
            val value = sharedPreferences.getString(key, CameraController.SCENE_MODE_DEFAULT)
            // n.b., on Android 4.3 emulator, scene mode is returned as null (this may be because it doesn't support
            // scene modes at all) - treat this the same as auto
            if (sceneMode == null) sceneMode = CameraController.SCENE_MODE_DEFAULT
            if (value != sceneMode) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "scene mode changed to: $value"
                )
                needReopen = true
            } else {
                if (applicationInterface.useCamera2()) {
                    // need to reopen if fake flash mode changed, as it changes the available camera features, and we can only set this after opening the camera
                    val camera2FakeFlash: Boolean =
                        preview.cameraController!!.useCamera2FakeFlash
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "camera2_fake_flash was: $camera2FakeFlash"
                    )
                    if (applicationInterface.useCamera2FakeFlash() != camera2FakeFlash) {
                        if (MyDebug.LOG) Log.d(TAG, "camera2_fake_flash changed")
                        needReopen = true
                    }
                }
            }

            if (!needReopen) {
                val oldTonemapProfile: TonemapProfile? = preview.cameraController?.tonemapProfile
                if (oldTonemapProfile !== TonemapProfile.TONEMAPPROFILE_OFF) {
                    val newTonemapProfile: TonemapProfile =
                        applicationInterface.getVideoTonemapProfile()
                    if (newTonemapProfile !== TonemapProfile.TONEMAPPROFILE_OFF && newTonemapProfile !== oldTonemapProfile) {
                        // needed for Galaxy S10e when changing from TONEMAP_MODE_CONTRAST_CURVE to TONEMAP_MODE_PRESET_CURVE,
                        // otherwise the contrast curve remains active!
                        if (MyDebug.LOG) Log.d(TAG, "switching between tonemap profiles")
                        needReopen = true
                    }
                }
            }

            if (!needReopen) {
                val oldIsExtension: Boolean = preview.cameraController?.isCameraExtension == true
                val newIsExtension: Boolean = applicationInterface.isCameraExtensionPref()
                if (oldIsExtension || newIsExtension) {
                    // At least on Galaxy S10e, we have problems stopping and starting a camera extension session,
                    // e.g., when changing resolutions whilst in an extension mode (XHDR or bokeh) or switching
                    // from XHDR to other modes (including non-extension modes like STD). Problems such as preview
                    // no longer receiving frames, or the call to createExtensionSession() (or createCaptureSession)
                    // hanging. So therefore we should reopen the camera if at least
                    // oldIsExtension==true.
                    // This isn't required if oldIsExtension==false but newIsExtension==true,
                    // but we still do so since reopening the camera occurs on a background thread
                    // (opening an extension session seems to take longer, so better not to block
                    // the UI thread).
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "need to reOpen Kamera for changes to extension session"
                    )
                    needReopen = true
                }
            }
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "need_reopen: $needReopen")
            Log.d(
                TAG,
                "updateForSettings: time after check need_reopen: " + (System.currentTimeMillis() - debugTime)
            )
        }

        mainUI.layoutUI() // needed in case we've changed UI placement or in "top" mode, if we've enabled/disabled on-screen UI icons
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "updateForSettings: time after layoutUI: " + (System.currentTimeMillis() - debugTime)
            )
        }

        // ensure icons invisible if disabling them from showing from the Settings
        // (if enabling them, we'll make the icon visible later on)
        checkDisableGUIIcons()

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val audioControl =
            sharedPreferences.getString(PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, "none")!!
        // better to only display the audio control icon if it matches specific known supported types
        // (important now that "voice" is no longer supported)
        //if( !audio_control.equals("voice") && !audio_control.equals("noise") ) {
        if (audioControl != "noise") {
            val speechRecognizerButton = findViewById<View>(R.id.audio_control)
            speechRecognizerButton.visibility = View.GONE
        }

        //speechControl.initSpeechRecognizer() // in case we've enabled or disabled speech recognizer

        // we no longer call initLocation() here (for having enabled or disabled geotagging), as that's
        // done in setWindowFlagsForCamera() - important not to call it here as well, otherwise if
        // permission wasn't granted, we'll ask for permission twice in a row (on Android 9 or earlier
        // at least)
        //initLocation() // in case we've enabled or disabled GPS
        initGyroSensors() // in case we've entered or left panorama
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "updateForSettings: time after init speech and location: " + (System.currentTimeMillis() - debugTime)
            )
        }
        if (toastMessage != null) blockStartupToast = true
        if (!updateCamera) {
            // don't try to update camera
        } else if (needReopen || preview.cameraController == null) { // if camera couldn't be opened before, might as well try again
            if (allowDim) applicationInterface.drawPreview.setDimPreview(true)
            preview.reOpenKamera()
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "updateForSettings: time after reopen: " + (System.currentTimeMillis() - debugTime)
                )
            }
        } else {
            preview.setCameraDisplayOrientation() // need to call in case the preview rotation option was changed
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "updateForSettings: time after set display orientation: " + (System.currentTimeMillis() - debugTime)
                )
            }
            if (allowDim) applicationInterface.drawPreview.setDimPreview(true)
            preview.pausePreview(true)
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "updateForSettings: time after pause: " + (System.currentTimeMillis() - debugTime)
                )
            }

            val handler = Handler()
            // We run setupCamera on the UI thread, but we do it on a post-delayed so that the dimming effect (for Camera2 API) has a chance to run.
            // Even if allowDim==false, still run as a postDelayed (a) for consistency, (b) to allow UI to run for a bit (to avoid risk of slow frames).
            handler.postDelayed(
                { preview.setupCamera(false) },
                DrawPreview.dimEffectTimeC + 16
            ) // +16 to allow time for a frame update to run
        }
        // don't set blockStartupToast to false yet, as camera might be closing/opening on background thread
        if (!toastMessage.isNullOrEmpty()) preview.showToast(
            null,
            toastMessage,
            true
        )

        // don't need to reset to savedFocusValue, as we'll have done this when setting up the camera (or will do so when the camera is reopened, if needReopen)
        /*if( savedFocusValue != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "switch focus back to: " + savedFocusValue)
    		preview.updateFocus(savedFocusValue, true, false)
    	}*/
        magneticSensor.registerMagneticListener(mSensorManager) // check whether we need to register or unregister the magnetic listener
        magneticSensor.checkMagneticAccuracy()

        if (MyDebug.LOG) {
            Log.d(TAG, "updateForSettings: done: " + (System.currentTimeMillis() - debugTime))
        }
    }

    /** Disables the optional on-screen icons if either user doesn't want to enable them, or not
     * supported. Note that displaying icons is done via MainUI.showGUI.
     * @return Whether an icon's visibility was changed.
     */
    fun checkDisableGUIIcons(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "checkDisableGUIIcons")
        return mainUI.getOnScreenIcons().checkDisableGUIIcons()
    }

    /** Closes and reopens the camera.
     * The camera will be closed and opened on a background thread, so won't be available upon
     * exit of this function.
     * @param blockStartupToast Whether to block the usual info toast that's displayed when opening the camera
     */
    fun reOpenKamera(blockStartupToast: Boolean) {
        this.blockStartupToast = blockStartupToast
        preview.reOpenKamera()
    }

    val preferenceFragment: MyPreferenceFragment?
        get() = fragmentManager.findFragmentByTag("PREFERENCE_FRAGMENT") as? MyPreferenceFragment

    private fun settingsIsOpen(): Boolean {
        return preferenceFragment != null
    }

    /** Call when the settings are going to be closed.
     */
    fun settingsClosing() {
        if (MyDebug.LOG) Log.d(TAG, "close settings")
        setWindowFlagsForCamera()
        showPreview(true)

        preferencesListener.stopListening()

        // Update the cached settings in DrawPreview
        // Note that some GUI related settings won't trigger preferencesListener.anyChange(), so
        // we always call this. Perhaps we could add more classifications to PreferencesListener
        // to mark settings that need us to update DrawPreview but not call updateForSettings().
        // However, DrawPreview.updateSettings() should be a quick function (the main point is
        // to avoid reading the preferences in every single frame).
        applicationInterface.drawPreview.updateSettings()

        // Set the flag to cover the preview until the camera is open and receiving frames again
        // (for Camera2 API) - avoids showing a flash of the preview from before the user went to
        // the settings.
        applicationInterface.drawPreview.setCoverPreview(true)

        if (preferencesListener.anyChange()) {
            mainUI.updateOnScreenIcons()
        }

        if (preferencesListener.anySignificantChange()) {
            // don't need to update camera, as we now pause/resume camera when going to settings
            updateForSettings(false)
        } else {
            if (MyDebug.LOG) Log.d(
                TAG,
                "no need to call updateForSettings() for changes made to preferences"
            )
            if (preferencesListener.anyChange()) {
                // however we should still destroy cached popup, in case UI settings need to be kept in
                // sync (e.g., changing the Repeat Mode)
                mainUI.destroyPopup()
            }
        }
    }

    private lateinit var popupOnBackPressedCallback: PopupOnBackPressedCallback

    private inner class PopupOnBackPressedCallback(enabled: Boolean) :
        OnBackPressedCallback(enabled) {
        override fun handleOnBackPressed() {
            if (MyDebug.LOG) Log.d(TAG, "PopupOnBackPressedCallback.handleOnBackPressed")
            if (popupIsOpen()) {
                // close popup will disable the PopupOnBackPressedCallback, so no need to do it here
                closePopup()
            } else {
                // shouldn't be here (if popup isn't open, this callback shouldn't be enabled), but just in case
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "PopupOnBackPressedCallback was enabled but popup menu not open?!"
                )
                this.isEnabled = false
                this@MainActivity.onBackPressed()
            }
        }
    }

    fun enablePopupOnBackPressedCallback(enabled: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "enablePopupOnBackPressedCallback: $enabled"
        )
        if (popupOnBackPressedCallback != null) {
            popupOnBackPressedCallback!!.isEnabled = enabled
        }
    }

    private lateinit var pausePreviewOnBackPressedCallback: PausePreviewOnBackPressedCallback

    private inner class PausePreviewOnBackPressedCallback(enabled: Boolean) :
        OnBackPressedCallback(enabled) {
        override fun handleOnBackPressed() {
            if (MyDebug.LOG) Log.d(TAG, "PausePreviewOnBackPressedCallback.handleOnBackPressed")

            if (::preview.isInitialized && preview.isPreviewPaused) {
                // starting the preview will disable the PausePreviewOnBackPressedCallback, so no need to do it here
                if (MyDebug.LOG) Log.d(TAG, "preview was paused, so unpause it")
                preview.startCameraPreview()
            } else {
                // shouldn't be here (if preview isn't paused, this callback shouldn't be enabled), but just in case
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "PausePreviewOnBackPressedCallback was enabled but preview not paused?!"
                )
                this.isEnabled = false
                this@MainActivity.onBackPressed()
            }
        }
    }

    fun enablePausePreviewOnBackPressedCallback(enabled: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "enablePausePreviewOnBackPressedCallback: $enabled"
        )
        if (pausePreviewOnBackPressedCallback != null) {
            pausePreviewOnBackPressedCallback!!.isEnabled = enabled
        }
    }

    private lateinit var screenLockOnBackPressedCallback: ScreenLockOnBackPressedCallback

    private inner class ScreenLockOnBackPressedCallback(enabled: Boolean) :
        OnBackPressedCallback(enabled) {
        override fun handleOnBackPressed() {
            if (MyDebug.LOG) Log.d(TAG, "ScreenLockOnBackPressedCallback.handleOnBackPressed")

            if (this@MainActivity.isScreenLocked) {
                preview.showToast(screenLockedToast, R.string.screen_is_locked)
            } else {
                // shouldn't be here (if screen isn't locked, this callback shouldn't be enabled), but just in case
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "ScreenLockOnBackPressedCallback was enabled but screen isn't locked?!"
                )
                this.isEnabled = false
                this@MainActivity.onBackPressed()
            }
        }
    }

    private fun enableScreenLockOnBackPressedCallback(enabled: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "enableScreenLockOnBackPressedCallback: $enabled"
        )
        if (screenLockOnBackPressedCallback != null) {
            screenLockOnBackPressedCallback!!.isEnabled = enabled
        }
    }

    // should no longer use onBackPressed() - instead use OnBackPressedCallback, for upcoming changes in Android 14+ (predictive back gestures)
    /*@Override
    public void onBackPressed() {
        if( MyDebug.LOG )
            Log.d(TAG, "onBackPressed")
        if( screenIsLocked ) {
            preview.showToast(screenLockedToast, R.string.screen_is_locked)
            return
        }

        if( settingsIsOpen() ) {
            settingsClosing()
        }
        else if( ::preview.isInitialized && preview.isPreviewPaused ) {
            if( MyDebug.LOG )
                Log.d(TAG, "preview was paused, so unpause it")
            preview.startCameraPreview()
            return
        }
        else {
            if( popupIsOpen() ) {
                closePopup()
                return
            }
        }
        super.onBackPressed()
    }*/

    /** Whether to allow the application to show under the navigation bar, or not.
     * Arguably we could enable this all the time, but in practice we only enable for cases when
     * wantNoLimits==true and navigationGap!=0 (if wantNoLimits==false, there's no need to
     * show under the navigation bar if navigationGap==0, there is no navigation bar).
     */
    private fun showUnderNavigation(enable: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "showUnderNavigation: $enable"
        )

        if (edgeToEdgeMode) {
            // we are already always in edge-to-edge mode
            return
        }

        run {
            // We used to use window flag FLAG_LAYOUT_NO_LIMITS, but this didn't work properly on
            // Android 11 (didn't take effect until orientation changed or application paused/resumed).
            // Although system ui visibility flags are deprecated on Android 11, this still works better
            // than the FLAG_LAYOUT_NO_LIMITS flag (which was not well documented anyway).
            // Update, now using WindowCompat.setDecorFitsSystemWindows. This is non-deprecated, and
            // documented at https://developer.android.com/develop/ui/views/layout/edge-to-edge-manually .
            /*int flags = getWindow().getDecorView().getSystemUiVisibility()
            if( enable ) {
                getWindow().getDecorView().setSystemUiVisibility(flags | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            }
            else {
                getWindow().getDecorView().setSystemUiVisibility(flags & ~View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            }*/
            testSetShowUnderNavigation = enable
            // in theory the VANILLA_ICE_CREAM is redundant as we shouldn't be here on Android 15+ anyway (since edgeToEdgeMode==true), but
            // wrapping in case this helps Google Play recommendation to avoid deprecated APIs for edge-to-edge
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                WindowCompat.setDecorFitsSystemWindows(window, !enable)
            }
        }

        // in theory the VANILLA_ICE_CREAM is redundant as we shouldn't be here on Android 15+ anyway (since edgeToEdgeMode==true), but
        // wrapping in case this helps Google Play recommendation to avoid deprecated APIs for edge-to-edge
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.navigationBarColor = if (enable) Color.TRANSPARENT else Color.BLACK
        }
    }

    /** The system is now such that we have entered or exited immersive mode. If visible is true,
     * system UI is now visible such that we should exit immersive mode. If visible is false, the
     * system has entered immersive mode.
     */
    private fun immersiveModeChanged(visible: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "immersiveModeChanged: $visible"
        )
        if (!usingKitKatImmersiveMode()) return

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
        val immersiveMode =
            sharedPreferences.getString(
                PreferenceKeys.IMMERSIVE_MODE_PREFERENCE_KEY,
                "immersive_mode_off"
            )!!
        val hideUi =
            immersiveMode == "immersive_mode_gui" || immersiveMode == "immersive_mode_everything"

        if (visible) {
            if (MyDebug.LOG) Log.d(TAG, "system bars now visible")
            // change UI due to having exited immersive mode
            if (hideUi) mainUI.setImmersiveMode(false)
            setImmersiveTimer()
        } else {
            if (MyDebug.LOG) Log.d(TAG, "system bars now NOT visible")
            // change UI due to having entered immersive mode
            if (hideUi) mainUI.setImmersiveMode(true)
        }
    }

    /** Set up listener to handle listening for system ui changes (for immersive mode), and setting
     * a WindowsInsetsListener to find the navigationGap.
     */
    private fun setupSystemUiVisibilityListener() {
        val decorView = window.decorView

        run {
            // set a window insets listener to find the navigationGap
            if (MyDebug.LOG) Log.d(TAG, "set a window insets listener")
            this.setWindowInsetsListener = true
            decorView.rootView.setOnApplyWindowInsetsListener(object :
                View.OnApplyWindowInsetsListener {
                private var hasLastSystemOrientation = false
                private var lastSystemOrientation: SystemOrientation? =
                    null

                override fun onApplyWindowInsets(
                    v: View,
                    windowInsets: WindowInsets
                ): WindowInsets {
                    if (MyDebug.LOG) Log.d(TAG, "onApplyWindowInsets")
                    val insetLeft: Int
                    val insetTop: Int
                    val insetRight: Int
                    val insetBottom: Int
                    if (this@MainActivity.edgeToEdgeMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // take opportunity to use non-deprecated versions also for edgeToEdgeMode==true, we need to use getInsetsIgnoringVisibility for
                        // immersive mode (since for edgeToEdgeMode==true, we are not using setSystemUiVisibility() / SYSTEM_UI_FLAG_LAYOUT_STABLE in setImmersiveMode())
                        // also compare with MyApplicationInterface.getDisplaySize() - in particular we don't care about caption/system bar that is returned on e.g.
                        // OnePlus Pad for insets.top when in landscape orientation (since the system bar isn't shown) however we also need to subtract any from the cutout -
                        // since this code is for finding what margins we need to set to avoid navigation bars avoiding the cutout is done below for the entire
                        // Open Kamera view
                        var insets: Insets? =
                            windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
                        var cutoutInsets: Insets? =
                            windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                        if (testForceWindowInsets) {
                            insets = testInsets
                            cutoutInsets = testCutoutInsets
                        }
                        insetLeft = insets!!.left - cutoutInsets!!.left
                        insetTop = insets.top - cutoutInsets.top
                        insetRight = insets.right - cutoutInsets.right
                        insetBottom = insets.bottom - cutoutInsets.bottom
                    } else {
                        insetLeft = windowInsets.systemWindowInsetLeft
                        insetTop = windowInsets.systemWindowInsetTop
                        insetRight = windowInsets.systemWindowInsetRight
                        insetBottom = windowInsets.systemWindowInsetBottom
                    }
                    if (MyDebug.LOG) {
                        Log.d(TAG, "inset left: $insetLeft")
                        Log.d(TAG, "inset top: $insetTop")
                        Log.d(TAG, "inset right: $insetRight")
                        Log.d(TAG, "inset bottom: $insetBottom")
                    }

                    if (this@MainActivity.edgeToEdgeMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // easier to ensure the entire activity avoids display cutouts - for the preview, we still support
                        // it showing under the navigation bar
                        var insets: Insets? =
                            windowInsets.getInsets(WindowInsets.Type.displayCutout())
                        if (testForceWindowInsets) {
                            insets = testCutoutInsets
                        }
                        v.setPadding(insets!!.left, insets.top, insets.right, insets.bottom)

                        // also handle change of immersive mode (instead of using deprecated setOnSystemUiVisibilityChangeListener below
                        immersiveModeChanged(windowInsets.isVisible(WindowInsets.Type.navigationBars()))
                    }

                    resetCachedSystemOrientation() // don't want to get cached result - this can sometimes happen e.g. on Pixel 6 Pro when switching between landscape and reverse landscape
                    val systemOrientation: SystemOrientation = this@MainActivity.systemOrientation
                    val newNavigationGap: Int
                    var newNavigationGapLandscape: Int
                    var newNavigationGapReverseLandscape: Int
                    when (systemOrientation) {
                        SystemOrientation.PORTRAIT -> {
                            if (MyDebug.LOG) Log.d(TAG, "portrait")
                            newNavigationGap = insetBottom
                            newNavigationGapLandscape = insetLeft
                            newNavigationGapReverseLandscape = insetRight
                        }

                        SystemOrientation.LANDSCAPE -> {
                            if (MyDebug.LOG) Log.d(TAG, "landscape")
                            newNavigationGap = insetRight
                            newNavigationGapLandscape = insetBottom
                            newNavigationGapReverseLandscape = insetTop
                        }

                        SystemOrientation.REVERSE_LANDSCAPE -> {
                            if (MyDebug.LOG) Log.d(TAG, "reverse landscape")
                            newNavigationGap = insetLeft
                            newNavigationGapLandscape = insetTop
                            newNavigationGapReverseLandscape = insetBottom
                        }

                        else -> {
                            Log.e(
                                TAG,
                                "unknown system_orientation?!: $systemOrientation"
                            )
                            newNavigationGap = 0
                            newNavigationGapLandscape = 0
                            newNavigationGapReverseLandscape = 0
                        }
                    }
                    if (!this@MainActivity.edgeToEdgeMode) {
                        // we only care about avoiding a landscape navigation bar (e.g., large tablets in landscape orientation) for edgeToEdgeMode==true
                        // in theory this could be useful when edgeToEdgeMode==false, but in practice we will never enter edge-to-edge-mode if the
                        // navigation bar is along the landscape-edge, so restrict behaviour change to edgeToEdgeMode==true
                        newNavigationGapLandscape = 0
                        newNavigationGapReverseLandscape = 0
                    }

                    // for edgeToEdgeMode==false, we only enter this case if system orientation changes, due to issues where this callback may be called first with 0 navigation gap
                    // (see notes below)
                    // for edgeToEdgeMode==true, simpler to always react to updated insets - in particular, in split-window mode, the navigation gaps can
                    // change when device rotates, even though the application remains in the same orientation
                    if ((this@MainActivity.edgeToEdgeMode || (hasLastSystemOrientation && systemOrientation != lastSystemOrientation)) && (newNavigationGap != _navigationGap || newNavigationGapLandscape != _navigationGapLandscape || newNavigationGapReverseLandscape != _navigationGapReverseLandscape)) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "navigation_gap changed from $_navigationGap to $newNavigationGap"
                        )

                        _navigationGap = newNavigationGap
                        _navigationGapLandscape = newNavigationGapLandscape
                        _navigationGapReverseLandscape = newNavigationGapReverseLandscape

                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "want_no_limits: $wantNoLimits"
                        )
                        if (wantNoLimits || this@MainActivity.edgeToEdgeMode) {
                            // If we want noLimits mode, then need to take care in case of device orientation
                            // in cases where that changes the navigationGap:
                            // - Need to set showUnderNavigation() (in case navigationGap when from zero to non-zero or vice versa).
                            // - Need to call layoutUI() (for different value of navigationGap)

                            // Need to call showUnderNavigation() from handler for it to take effect.
                            // Similarly, we have problems if we call layoutUI without post-ing it -
                            // sometimes when rotating a device, we get a call to OnApplyWindowInsetsListener
                            // with 0 navigationGap followed by the call with the correct non-zero values -
                            // posting the call to layoutUI means it runs after the second call, so we have the
                            // correct navigationGap.

                            val handler = Handler()
                            handler.post {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "runnable for change in navigation_gap due to orientation change"
                                )
                                if (_navigationGap != 0) {
                                    if (MyDebug.LOG) Log.d(
                                        TAG,
                                        "set FLAG_LAYOUT_NO_LIMITS"
                                    )
                                    showUnderNavigation(true)
                                } else {
                                    if (MyDebug.LOG) Log.d(
                                        TAG,
                                        "clear FLAG_LAYOUT_NO_LIMITS"
                                    )
                                    showUnderNavigation(false)
                                }
                                // needed for OnePlus Pad when rotating, to avoid delay in updating lastTakePhotoTopTime (affects placement of on-screen text e.g. zoom)
                                // need to do this from handler for this to take effect (otherwise lastTakePhotoTopTime won't update to new value)
                                applicationInterface.drawPreview.onNavigationGapChanged()

                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "layout UI due to changing navigation_gap"
                                )
                                mainUI.layoutUI()
                            }
                        }
                    } else if (!this@MainActivity.edgeToEdgeMode && _navigationGap == 0) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "navigation_gap changed from zero to $newNavigationGap"
                        )
                        _navigationGap = newNavigationGap
                        // Sometimes when this callback is called, the navigationGap may still be 0 even if
                        // the device doesn't have physical navigation buttons - we need to wait
                        // until we have found a non-zero value before switching to no limits.
                        // On devices with physical navigation bar, navigationGap should remain 0
                        // (and there's no point setting FLAG_LAYOUT_NO_LIMITS)
                        if (wantNoLimits && _navigationGap != 0) {
                            if (MyDebug.LOG) Log.d(TAG, "set FLAG_LAYOUT_NO_LIMITS")
                            showUnderNavigation(true)
                        }
                    }

                    if (hasLastSystemOrientation && ((systemOrientation == SystemOrientation.LANDSCAPE && lastSystemOrientation == SystemOrientation.REVERSE_LANDSCAPE) ||
                                (systemOrientation == SystemOrientation.REVERSE_LANDSCAPE && lastSystemOrientation == SystemOrientation.LANDSCAPE)
                                )
                    ) {
                        // hack - this should be done via MyDisplayListener.onDisplayChanged(), but that doesn't work on Galaxy S24+ (either MyDisplayListener.onDisplayChanged()
                        // isn't called, or getDefaultDisplay().getRotation() is still returning the old rotation)
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "onApplyWindowInsets: switched between landscape and reverse orientation"
                        )
                        onSystemOrientationChanged()
                    }

                    hasLastSystemOrientation = true
                    lastSystemOrientation = systemOrientation

                    // see comments in MainUI.layoutUI() for why we don't use this
                    /*if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getSystemOrientation() == SystemOrientation.LANDSCAPE ) {
                        Rect privacyIndicatorRect = windowInsets.getPrivacyIndicatorBounds()
                        if( privacyIndicatorRect != null ) {
                            Rect windowBounds = getWindowManager().getCurrentWindowMetrics().getBounds()
                            if( MyDebug.LOG ) {
                                Log.d(TAG, "privacyIndicatorRect: " + privacyIndicatorRect)
                                Log.d(TAG, "windowBounds: " + windowBounds)
                            }
                            privacyIndicatorGap = window_bounds.right - privacy_indicator_rect.left
                            if( privacyIndicatorGap < 0 )
                                privacyIndicatorGap = 0 // just in case??
                            if( MyDebug.LOG )
                                Log.d(TAG, "privacyIndicatorGap: " + privacyIndicatorGap)
                        }
                    }
                    else {
                        privacyIndicatorGap = 0
                    }*/
                    return window.decorView.rootView.onApplyWindowInsets(windowInsets)
                }
            })
        }

        if (edgeToEdgeMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // already handled by the setOnApplyWindowInsetsListener above
        } else {
            decorView.setOnSystemUiVisibilityChangeListener { visibility -> // Note that system bars will only be "visible" if none of the
                // LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.

                if (MyDebug.LOG) Log.d(
                    TAG,
                    "onSystemUiVisibilityChange: $visibility"
                )

                // Note that Android example code says to test against SYSTEM_UI_FLAG_FULLSCREEN,
                // but this stopped working on Android 11, as when calling setSystemUiVisibility(0)
                // to exit immersive mode, when we arrive here the flag SYSTEM_UI_FLAG_FULLSCREEN
                // is still set. Fixed by checking for SYSTEM_UI_FLAG_HIDE_NAVIGATION instead -
                // which makes some sense since we run in fullscreen mode all the time anyway.
                //if( (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 ) {
                if ((visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                    immersiveModeChanged(true)
                } else {
                    immersiveModeChanged(false)
                }
            }
        }
    }

    fun usingKitKatImmersiveMode(): Boolean {
        // whether we are using a Kit Kat style immersive mode (either hiding navigation bar, GUI, or everything)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val immersiveMode =
            sharedPreferences.getString(
                PreferenceKeys.IMMERSIVE_MODE_PREFERENCE_KEY,
                "immersive_mode_off"
            )!!
        return immersiveMode == "immersive_mode_navigation" || immersiveMode == "immersive_mode_gui" || immersiveMode == "immersive_mode_everything"
    }

    fun usingKitKatImmersiveModeEverything(): Boolean {
        // whether we are using a Kit Kat style immersive mode for everything
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val immersiveMode =
            sharedPreferences.getString(
                PreferenceKeys.IMMERSIVE_MODE_PREFERENCE_KEY,
                "immersive_mode_off"
            )!!
        return immersiveMode == "immersive_mode_everything"
    }


    private var immersiveTimerHandler: Handler? = null
    private var immersiveTimerRunnable: Runnable? = null

    private fun cancelImmersiveTimer() {
        if (immersiveTimerHandler != null && immersiveTimerRunnable != null) {
            immersiveTimerHandler!!.removeCallbacks(immersiveTimerRunnable!!)
            immersiveTimerHandler = null
            immersiveTimerRunnable = null
        }
    }

    private fun setImmersiveTimer() {
        cancelImmersiveTimer()
        if (isAppPaused) {
            // don't want to enter immersive mode from background
            // problem that even after onPause, we can end up here via various callbacks
            return
        }
        immersiveTimerHandler = Handler()
        immersiveTimerHandler!!.postDelayed(Runnable {
            if (MyDebug.LOG) Log.d(TAG, "setImmersiveTimer: run")
            // even though timer should have been cancelled when in background, check appIsPaused just in case
            if (!this@MainActivity.isAppPaused && !this@MainActivity.isCameraInBackground && !popupIsOpen() && usingKitKatImmersiveMode()) setImmersiveMode(
                true
            )
        }.also { immersiveTimerRunnable = it }, 5000)
    }

    fun initImmersiveMode() {
        if (!usingKitKatImmersiveMode()) {
            setImmersiveMode(true)
        } else {
            // don't start in immersive mode, only after a timer
            setImmersiveTimer()
        }
    }

    fun setImmersiveMode(on: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "setImmersiveMode: $on")

        // n.b., preview.setImmersiveMode() is called from onSystemUiVisibilityChange()

        // don't allow the kitkat-style immersive mode for panorama mode (problem that in "full" immersive mode, the gyro spot can't be seen - we could fix this, but simplest to just disallow)
        val enableImmersive =
            on && usingKitKatImmersiveMode() && applicationInterface.photoMode !== PhotoMode.Panorama
        if (MyDebug.LOG) Log.d(
            TAG,
            "enable_immersive?: $enableImmersive"
        )

        if (edgeToEdgeMode) {
            // take opportunity to avoid deprecated setSystemUiVisibility
            val windowInsetsController = WindowCompat.getInsetsController(
                window, window.decorView
            )
            val type =
                WindowInsetsCompat.Type.navigationBars() // only show/hide navigation bars, as we run with system bars always hidden
            if (enableImmersive) {
                windowInsetsController.hide(type)
            } else {
                windowInsetsController.show(type)
            }
        } else {
            // save whether we set SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION - since this flag might be enabled for showUnderNavigation(true), at least indirectly by setDecorFitsSystemWindows() on old versions of Android
            val savedFlags =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (MyDebug.LOG) Log.d(
                TAG,
                "saved_flags?: $savedFlags"
            )
            if (enableImmersive) {
                window.decorView.systemUiVisibility =
                    savedFlags or View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            } else {
                window.decorView.systemUiVisibility = savedFlags
            }
        }
    }

    /** Sets the brightness level for normal operation (when camera preview is visible).
     * If forceMax is true, this always forces maximum brightness otherwise this depends on user preference.
     */
    fun setBrightnessForCamera(forceMax: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "setBrightnessForCamera")
        // set screen to max brightness - see http://stackoverflow.com/questions/11978042/android-screen-brightness-max-value
        // done here rather than onCreate, so that changing it in preferences takes effect without restarting app
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val layout = window.attributes
        if (forceMax || sharedPreferences.getBoolean(
                PreferenceKeys.MAX_BRIGHTNESS_PREFERENCE_KEY,
                false
            )
        ) {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        } else {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        // this must be called from the ui thread
        // sometimes this method may be called not on UI thread, e.g., Preview.takePhotoWhenFocused->CameraController2.takePicture
        // ->CameraController2.runFakePrecapture->Preview/onFrontScreenTurnOn->MyApplicationInterface.turnFrontScreenFlashOn
        // -> this.setBrightnessForCamera
        this.runOnUiThread { window.attributes = layout }
    }

    /**
     * Set the brightness to minimal in case the preference key is set to do it
     */
    fun setBrightnessToMinimumIfWanted() {
        if (MyDebug.LOG) Log.d(TAG, "setBrightnessToMinimum")
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val layout = window.attributes
        if (sharedPreferences.getBoolean(
                PreferenceKeys.DIM_WHEN_DISCONNECTED_PREFERENCE_KEY,
                false
            )
        ) {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        } else {
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        this.runOnUiThread { window.attributes = layout }
    }

    /** Sets the window flags for normal operation (when camera preview is visible).
     */
    fun setWindowFlagsForCamera() {
        if (MyDebug.LOG) Log.d(TAG, "setWindowFlagsForCamera")
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // we set this to prevent what's on the preview being used to show under the "recent apps" view - potentially useful
            // for privacy reasons
            setRecentsScreenshotEnabled(false)
        }

        requestedOrientation = if (LOCK_TO_LANDSCAPE) {
            // force to landscape mode
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) // testing for devices with unusual sensor orientation (e.g., Nexus 5X)
        } else {
            // allow orientation to change for camera, even if user has locked orientation
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        if (::preview.isInitialized) {
            // also need to call preview.setCameraDisplayOrientation, as this handles if the user switched from portrait to reverse landscape whilst in settings/etc
            // as switching from reverse landscape back to landscape isn't detected in onConfigurationChanged
            // update: now probably irrelevant now that we close/reopen the camera, but keep it here anyway
            preview.setCameraDisplayOrientation()
        }
        if (::preview.isInitialized && ::mainUI.isInitialized) {
            // layoutUI() is needed because even though we call layoutUI from MainUI.onOrientationChanged(), certain things
            // (uiRotation) depend on the system orientation too.
            // Without this, going to Settings, then changing orientation, then exiting settings, would show the icons with the
            // wrong orientation.
            // We put this here instead of onConfigurationChanged() as onConfigurationChanged() isn't called when switching from
            // reverse landscape to landscape orientation: so it's needed to fix if the user starts in portrait, goes to settings
            // or a dialog, then switches to reverse landscape, then exits settings/dialog - the system orientation will switch
            // to landscape (which Open Kamera is forced to).
            mainUI.layoutUI()
        }


        // keep screen active - see http://stackoverflow.com/questions/2131948/force-screen-on
        if (sharedPreferences.getBoolean(PreferenceKeys.KEEP_DISPLAY_ON_PREFERENCE_KEY, true)) {
            if (MyDebug.LOG) Log.d(TAG, "do keep screen on")
            this.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            if (MyDebug.LOG) Log.d(TAG, "don't keep screen on")
            this.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (sharedPreferences.getBoolean(PreferenceKeys.SHOW_WHEN_LOCKED_PREFERENCE_KEY, false)) {
            if (MyDebug.LOG) Log.d(TAG, "do show when locked")
            // keep Open Kamera on top of screen-lock (will still need to unlock when going to gallery or settings)
            showWhenLocked(true)
        } else {
            if (MyDebug.LOG) Log.d(TAG, "don't show when locked")
            showWhenLocked(false)
        }

        if (wantNoLimits && _navigationGap != 0) {
            if (MyDebug.LOG) Log.d(TAG, "set FLAG_LAYOUT_NO_LIMITS")
            showUnderNavigation(true)
        }

        setBrightnessForCamera(false)

        initImmersiveMode()
        isCameraInBackground = false

        magneticSensor.clearDialog() // if the magnetic accuracy was opened, it must have been closed now
        if (!isAppPaused) {
            // Needs to be called after cameraInBackground is set to false.
            // Note that the appIsPaused guard is in some sense unnecessary, as initLocation tests for that too,
            // but useful for error tracking - ideally we want to make sure that initLocation is never called when
            // app is paused. It can happen here because setWindowFlagsForCamera() is called from
            // onCreate()
            initLocation()

            // Similarly only want to reopen the camera if no longer paused
            if (::preview.isInitialized) {
                preview.onResume()
            }
        }
    }

    private fun setWindowFlagsForSettings() {
        setWindowFlagsForSettings(true)
    }

    /** Sets the window flags for when the settings window is open.
     * @param setLockProtect If true, then window flags will be set to protect by screen lock, no
     * matter what the preference setting
     * PreferenceKeys.getShowWhenLockedPreferenceKey() is set to. This
     * should be true for the Settings window, and anything else that might
     * need protecting. But some callers use this method for opening other
     * things (such as info dialogs).
     */
    fun setWindowFlagsForSettings(setLockProtect: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setWindowFlagsForSettings: $setLockProtect"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // in settings mode, okay to revert to default behaviour for using a screenshot for "recent apps" view
            setRecentsScreenshotEnabled(true)
        }

        // allow screen rotation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // revert to standard screen blank behaviour
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (wantNoLimits && _navigationGap != 0) {
            if (MyDebug.LOG) Log.d(TAG, "clear FLAG_LAYOUT_NO_LIMITS")
            showUnderNavigation(false)
        }
        if (setLockProtect) {
            // settings should still be protected by screen lock
            showWhenLocked(false)
        }

        run {
            val layout = window.attributes
            layout.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = layout
        }

        setImmersiveMode(false)
        isCameraInBackground = true

        // we disable location listening when showing settings or a dialog etc - saves battery life, also better for privacy
        applicationInterface.locationSupplier.freeLocationListeners()

        // similarly we close the camera
        preview.onPause(false)

        pushSwitchedCamera = false // just in case
    }

    private fun showWhenLocked(show: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "showWhenLocked: $show")
        // although FLAG_SHOW_WHEN_LOCKED is deprecated, setShowWhenLocked(false) does not work
        // correctly: if we turn screen off and on when camera is open (so we're now running above
        // the lock screen), going to settings does not show the lock screen, i.e.,
        // setShowWhenLocked(false) does not take effect!
        /*if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			if( MyDebug.LOG )
				Log.d(TAG, "use setShowWhenLocked")
			setShowWhenLocked(show)
		}
		else*/
        run {
            if (show) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }
    }

    /** Use this is place of simply alert.show(), if the orientation has just been set to allow
     * rotation via setWindowFlagsForSettings(). On some devices (e.g., OnePlus 3T with Android 8),
     * the dialog doesn't show properly if the phone is held in portrait. A workaround seems to be
     * to use postDelayed. Note that postOnAnimation() doesn't work.
     */
    fun showAlert(alert: AlertDialog) {
        if (MyDebug.LOG) Log.d(TAG, "showAlert")
        val handler = Handler()
        handler.postDelayed({ alert.show() }, 20)
        // note that 1ms usually fixes the problem, but not always 10ms seems fine, have set 20ms
        // just in case
    }

    fun showPreview(show: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "showPreview: $show")
        val container = findViewById<ViewGroup>(R.id.hide_container)
        container.visibility = if (show) View.GONE else View.VISIBLE
    }

    /** Rotates the supplied bitmap according to the orientation tag stored in the exif data. If no
     * rotation is required, the input bitmap is returned. If rotation is required, the input
     * bitmap is recycled.
     * @param uri Uri containing the JPEG with Exif information to use.
     */
    @Throws(IOException::class)
    fun rotateForExif(bitmap: Bitmap, uri: Uri): Bitmap {
        var bitmap = bitmap
        var exif: ExifInterface? = null
        var inputStream: InputStream? = null
        try {
            inputStream = this.contentResolver.openInputStream(uri)
            exif = ExifInterface(inputStream)
        } catch (_: Exception) {
            // do nothing
        } finally {
            inputStream?.close()
        }

        if (exif != null) {
            val exifOrientationS = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            var needsTf = false
            var exifOrientation = 0
            // see http://jpegclub.org/exif_orientation.html
            // and http://stackoverflow.com/questions/20478765/how-to-get-the-correct-orientation-of-the-image-selected-from-the-default-image
            if (exifOrientationS == ExifInterface.ORIENTATION_UNDEFINED || exifOrientationS == ExifInterface.ORIENTATION_NORMAL) {
                // leave unchanged
            } else if (exifOrientationS == ExifInterface.ORIENTATION_ROTATE_180) {
                needsTf = true
                exifOrientation = 180
            } else if (exifOrientationS == ExifInterface.ORIENTATION_ROTATE_90) {
                needsTf = true
                exifOrientation = 90
            } else if (exifOrientationS == ExifInterface.ORIENTATION_ROTATE_270) {
                needsTf = true
                exifOrientation = 270
            } else {
                // just leave unchanged for now
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "    unsupported exif orientation: $exifOrientationS"
                )
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "    exif orientation: $exifOrientation"
            )

            if (needsTf) {
                if (MyDebug.LOG) Log.d(TAG, "    need to rotate bitmap due to exif orientation tag")
                val m = Matrix()
                m.setRotate(exifOrientation.toFloat(), bitmap.width * 0.5f, bitmap.height * 0.5f)
                val rotatedBitmap =
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = rotatedBitmap
                }
            }
        }
        return bitmap
    }

    /** Loads a thumbnail from the supplied image uri (not videos). Note this loads from the bitmap
     * rather than reading from MediaStore. Therefore, this works with SAF uris as well as
     * MediaStore uris, as well as allowing control over the resolution of the thumbnail.
     * If sampleFactor is 1, this returns a bitmap scaled to match the display resolution. If
     * sampleFactor is greater than 1, it will be scaled down to a lower resolution.
     * We now use this for photos in preference to APIs like
     * MediaStore.Images.Thumbnails.getThumbnail(). Advantages are simplifying the code, reducing
     * number of different codepaths, but also seems to help against device specific bugs
     * in getThumbnail() e.g. Pixel 6 Pro with x-night in portrait.
     */
    private fun loadThumbnailFromUri(uri: Uri, sampleFactor: Int): Bitmap? {
        var thumbnail: Bitmap? = null
        try {
            //thumbnail = MediaStore.Images.Media.getBitmap(getContentResolver(), media.uri)
            // only need to load a bitmap as large as the screen size
            val options = BitmapFactory.Options()
            var `is` = contentResolver.openInputStream(uri)
            // get dimensions
            options.inJustDecodeBounds = true
            BitmapFactory.decodeStream(`is`, null, options)
            var bitmapWidth = options.outWidth
            var bitmapHeight = options.outHeight
            val displaySize = Point()
            applicationInterface.getDisplaySize(displaySize, true)
            if (MyDebug.LOG) {
                Log.d(TAG, "bitmap_width: $bitmapWidth")
                Log.d(TAG, "bitmap_height: $bitmapHeight")
                Log.d(TAG, "display width: " + displaySize.x)
                Log.d(TAG, "display height: " + displaySize.y)
            }
            // align dimensions
            if (displaySize.x < displaySize.y) {
                displaySize[displaySize.y] = displaySize.x
            }
            if (bitmapWidth < bitmapHeight) {
                val dummy = bitmapWidth
                bitmapWidth = bitmapHeight
                bitmapHeight = dummy
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "bitmap_width: $bitmapWidth")
                Log.d(TAG, "bitmap_height: $bitmapHeight")
                Log.d(TAG, "display width: " + displaySize.x)
                Log.d(TAG, "display height: " + displaySize.y)
            }
            // only care about height, to save worrying about different aspect ratios
            options.inSampleSize = 1
            while (bitmapHeight / (2 * options.inSampleSize) >= displaySize.y) {
                options.inSampleSize *= 2
            }
            options.inSampleSize *= sampleFactor
            if (MyDebug.LOG) {
                Log.d(TAG, "inSampleSize: " + options.inSampleSize)
            }
            options.inJustDecodeBounds = false
            // need a new inputstream, see https://stackoverflow.com/questions/2503628/bitmapfactory-decodestream-returning-null-when-options-are-set
            `is`!!.close()
            `is` = contentResolver.openInputStream(uri)
            thumbnail = BitmapFactory.decodeStream(`is`, null, options)
            if (thumbnail == null) {
                Log.e(TAG, "decodeStream returned null bitmap for ghost image last")
            }
            `is`!!.close()

            thumbnail = rotateForExif(thumbnail!!, uri)
        } catch (e: IOException) {
            Log.e(TAG, "failed to load bitmap for ghost image last")
            e.printStackTrace()
        }
        return thumbnail
    }

    /** Shows the default "blank" gallery icon, when we don't have a thumbnail available.
     */
    private fun updateGalleryIconToBlank() {
        if (MyDebug.LOG) Log.d(TAG, "updateGalleryIconToBlank")
        val galleryButton = this.findViewById<ImageButton>(R.id.gallery)
        val bottom = galleryButton.paddingBottom
        val top = galleryButton.paddingTop
        val right = galleryButton.paddingRight
        val left = galleryButton.paddingLeft
        /*if( MyDebug.LOG )
            Log.d(TAG, "padding: " + bottom)*/
        galleryButton.setImageBitmap(null)
        galleryButton.setImageResource(R.drawable.baseline_photo_library_white_48)
        // workaround for setImageResource also resetting padding, Android bug
        galleryButton.setPadding(left, top, right, bottom)
        galleryBitmap = null
    }

    /** Shows a thumbnail for the gallery icon.
     */
    fun updateGalleryIcon(thumbnail: Bitmap) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "updateGalleryIcon: $thumbnail"
        )
        // If we're currently running the background task to update the gallery (see updateGalleryIcon()), we should cancel that!
        // Otherwise, if user takes a photo whilst the background task is still running, the thumbnail from the latest photo will
        // be overridden when the background task completes. This is more likely when using SAF on Android 10+ with scoped storage,
        // due to SAF's poor performance for folders with large number of files.
        if (updateGalleryFuture != null) {
            if (MyDebug.LOG) Log.d(TAG, "cancel update_gallery_future")
            updateGalleryFuture!!.cancel(true)
        }
        val galleryButton = this.findViewById<ImageButton>(R.id.gallery)
        galleryButton.setImageBitmap(thumbnail)
        galleryBitmap = thumbnail
    }

    /** Updates the gallery icon by searching for the most recent photo.
     * Launches the task in a separate thread.
     */
    fun updateGalleryIcon() {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "updateGalleryIcon")
            debugTime = System.currentTimeMillis()
        }
        if (updateGalleryFuture != null) {
            Log.d(TAG, "previous updateGalleryIcon task already running")
            return
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val ghostImagePref =
            sharedPreferences.getString(
                PreferenceKeys.GHOST_IMAGE_PREFERENCE_KEY,
                "preference_ghost_image_off"
            )!!
        val ghostImageLast = ghostImagePref == "preference_ghost_image_last"

        val handler = Handler(Looper.getMainLooper())

        //new AsyncTask<Void, Void, Bitmap>() {
        val runnable: Runnable = object : Runnable {
            private val TAG = "updateGalleryIcon"
            private var uri: Uri? = null
            private var isRaw = false
            private var isVideo = false

            //protected Bitmap doInBackground(Void... params) {
            override fun run() {
                if (MyDebug.LOG) Log.d(TAG, "doInBackground")
                val media: StorageUtils.Media? = applicationInterface.storageUtils.latestMedia
                var thumbnail: Bitmap? = null
                val keyguardManager =
                    this@MainActivity.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                val isLocked =
                    keyguardManager != null && keyguardManager.inKeyguardRestrictedInputMode()
                if (MyDebug.LOG) Log.d(TAG, "is_locked?: $isLocked")
                if (media != null && contentResolver != null && !isLocked) {
                    // check for getContentResolver() != null, as have had reported Google Play crashes

                    uri = media.getMediaStoreUri(this@MainActivity)
                    isRaw = media.filename != null && StorageUtils.filenameIsRaw(media.filename)
                    isVideo = media.video

                    if (ghostImageLast && !media.video) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "load full size bitmap for ghost image last photo"
                        )
                        // use sample factor of 1 so that it's full size for ghost image
                        thumbnail = loadThumbnailFromUri(media.uri, 1)
                    }
                    if (thumbnail == null) {
                        try {
                            if (!media.video) {
                                if (MyDebug.LOG) Log.d(TAG, "load thumbnail for photo")
                                // use sample factor as this image is only used for thumbnail and
                                // unlike code in MyApplicationInterface.saveImage() we don't need to
                                // worry about the thumbnail animation when taking/saving a photo
                                thumbnail = loadThumbnailFromUri(media.uri, 8)
                            } else if (!media.mediastore) {
                                if (MyDebug.LOG) Log.d(TAG, "load thumbnail for video from SAF uri")
                                var pfdSaf: ParcelFileDescriptor? =
                                    null // keep a reference to this as long as retriever, to avoid risk of pfdSaf being garbage collected
                                val retriever = MediaMetadataRetriever()
                                try {
                                    pfdSaf = contentResolver.openFileDescriptor(media.uri, "r")
                                    retriever.setDataSource(pfdSaf!!.fileDescriptor)
                                    thumbnail = retriever.getFrameAtTime(-1)
                                } catch (e: Exception) {
                                    Log.d(TAG, "failed to load video thumbnail")
                                    e.printStackTrace()
                                } finally {
                                    try {
                                        retriever.release()
                                    } catch (ex: RuntimeException) {
                                        // ignore
                                    }
                                    try {
                                        pfdSaf?.close()
                                    } catch (e: IOException) {
                                        e.printStackTrace()
                                    }
                                }
                            } else {
                                if (MyDebug.LOG) Log.d(TAG, "load thumbnail for video")
                                thumbnail = MediaStore.Video.Thumbnails.getThumbnail(
                                    contentResolver,
                                    media.id,
                                    MediaStore.Video.Thumbnails.MINI_KIND,
                                    null
                                )
                            }
                        } catch (exception: Throwable) {
                            // have had Google Play NoClassDefFoundError crashes from getThumbnail() for Galaxy Ace4 (vivalto3g), Galaxy S Duos3 (vivalto3gvn)
                            // also NegativeArraySizeException - best to catch everything
                            if (MyDebug.LOG) Log.e(TAG, "thumbnail exception")
                            exception.printStackTrace()
                        }
                    }
                }

                //return thumbnail
                val thumbnailF = thumbnail
                handler.post { onPostExecute(thumbnailF) }
            }

            /** Runs on UI thread, after background work is complete.
             */
            fun onPostExecute(thumbnail: Bitmap?) {
                if (MyDebug.LOG) Log.d(TAG, "onPostExecute")
                if (updateGalleryFuture != null && updateGalleryFuture!!.isCancelled) {
                    if (MyDebug.LOG) Log.d(TAG, "was cancelled")
                    updateGalleryFuture = null
                    return
                }
                // since we're now setting the thumbnail to the latest media on disk, we need to make sure clicking the Gallery goes to this
                applicationInterface.storageUtils.clearLastMediaScanned()
                if (uri != null) {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "found media uri: $uri")
                        Log.d(TAG, "    is_raw?: $isRaw")
                    }
                    applicationInterface.storageUtils
                        .setLastMediaScanned(uri, isRaw, false, null)
                }
                if (thumbnail != null) {
                    if (MyDebug.LOG) Log.d(TAG, "set gallery button to thumbnail")
                    updateGalleryIcon(thumbnail)
                    applicationInterface.drawPreview.updateThumbnail(
                        thumbnail,
                        isVideo,
                        false
                    ) // needed in case last ghost image is enabled
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "set gallery button to blank")
                    updateGalleryIconToBlank()
                }

                updateGalleryFuture = null
            } //}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        }

        val executor = Executors.newSingleThreadExecutor()
        //executor.execute(runnable)
        updateGalleryFuture = executor.submit(runnable)

        if (MyDebug.LOG) Log.d(
            TAG,
            "updateGalleryIcon: total time to update gallery icon: " + (System.currentTimeMillis() - debugTime)
        )
    }

    fun savingImage(started: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "savingImage: $started")

        this.runOnUiThread {
            val galleryButton = findViewById<ImageButton>(R.id.gallery)
            if (started) {
                //galleryButton.setColorFilter(0x80ffffff, PorterDuff.Mode.MULTIPLY)
                if (gallerySaveAnim == null) {
                    gallerySaveAnim = ValueAnimator.ofInt(
                        Color.argb(200, 255, 255, 255),
                        Color.argb(63, 255, 255, 255)
                    )
                    gallerySaveAnim!!.setEvaluator(ArgbEvaluator())
                    gallerySaveAnim!!.repeatCount = ValueAnimator.INFINITE
                    gallerySaveAnim!!.repeatMode = ValueAnimator.REVERSE
                    gallerySaveAnim!!.duration = 500
                }
                gallerySaveAnim!!.addUpdateListener { animation ->
                    galleryButton.setColorFilter(
                        (animation.animatedValue as Int),
                        PorterDuff.Mode.MULTIPLY
                    )
                }
                gallerySaveAnim!!.start()
            } else if (gallerySaveAnim != null) {
                gallerySaveAnim!!.cancel()
            }
            galleryButton.colorFilter = null
        }
    }

    /** Called when the number of images being saved in ImageSaver changes (or otherwise something
     * that changes our calculation of whether we can take a new photo, e.g., changing photo mode).
     */
    fun imageQueueChanged() {
        if (MyDebug.LOG) Log.d(TAG, "imageQueueChanged")
        applicationInterface.drawPreview
            .setImageQueueFull(!applicationInterface.canTakeNewPhoto())

        /*if( applicationInterface.imageSaver.getNImagesToSave() == 0) {
            cancelImageSavingNotification()
        }
        else if( hasNotification ) {
            // call again to update the text of remaining images
            createImageSavingNotification()
        }*/
    }

    /** Creates a notification to indicate still saving images (or updates an existing one).
     * Update: notifications now removed due to needing permissions on Android 13+.
     */
    private fun createImageSavingNotification() {
        if (MyDebug.LOG) Log.d(TAG, "createImageSavingNotification")
        /*if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            int nImagesToSave = applicationInterface.imageSaver.getNRealImagesToSave()
            Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notify_take_photo)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.image_saving_notification) + " " + nImagesToSave + " " + getString(R.string.remaining))
                    //.setStyle(new Notification.BigTextStyle()
                    //        .bigText("Much longer text that cannot fit one line..."))
                    //.setPriority(Notification.PRIORITY_DEFAULT)

            NotificationManager notificationManager = getSystemService(NotificationManager.class)
            notificationManager.notify(imageSavingNotificationId, builder.build())
            hasNotification = true
        }*/
    }

    /** Cancels the notification for saving images.
     * Update: notifications now removed due to needing permissions on Android 13+.
     */
    private fun cancelImageSavingNotification() {
        if (MyDebug.LOG) Log.d(TAG, "cancelImageSavingNotification")
        /*if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class)
            notificationManager.cancel(imageSavingNotificationId)
            hasNotification = false
        }*/
    }

    fun clickedGallery(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedGallery")
        openGallery()
    }

    private fun openGallery() {
        if (MyDebug.LOG) Log.d(TAG, "openGallery")
        //Intent intent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        var uri: Uri? = applicationInterface.storageUtils.lastMediaScanned
        var isRaw = uri != null && applicationInterface.storageUtils.lastMediaScannedIsRaw
        if (MyDebug.LOG && uri != null) {
            Log.d(TAG, "found cached most recent uri: $uri")
            Log.d(TAG, "    is_raw: $isRaw")
        }
        if (uri == null) {
            if (MyDebug.LOG) Log.d(TAG, "go to latest media")
            val media: StorageUtils.Media? = applicationInterface.storageUtils.latestMedia
            if (media != null) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "latest uri:" + media.uri)
                    Log.d(TAG, "filename: " + media.filename)
                }
                uri = media.getMediaStoreUri(this)
                if (MyDebug.LOG) Log.d(TAG, "media uri:$uri")
                isRaw = media.filename != null && StorageUtils.filenameIsRaw(media.filename)
                if (MyDebug.LOG) Log.d(TAG, "is_raw:$isRaw")
            }
        }

        if (uri != null && !useScopedStorage()) {
            // check uri exists
            // note, with scoped storage this isn't reliable when using SAF - since we don't actually have permission to access mediastore URIs that
            // were created via Storage Access Framework, even though Open Kamera was the application that saved them(!)
            try {
                val cr = contentResolver
                val pfd = cr.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "uri no longer exists (1): $uri"
                    )
                    uri = null
                    isRaw = false
                } else {
                    pfd.close()
                }
            } catch (e: IOException) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "uri no longer exists (2): $uri"
                )
                uri = null
                isRaw = false
            }
        }
        if (uri == null) {
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            isRaw = false
        }
        if (!isTest) {
            // don't do if testing, as unclear how to exit activity to finish test (for testGallery())
            if (MyDebug.LOG) Log.d(TAG, "launch uri:$uri")
            val reviewAction = "com.android.camera.action.REVIEW"
            var done = false
            if (!isRaw) {
                // reviewAction means we can view video files without autoplaying.
                // However, Google Photos at least has problems with going to a RAW photo (in RAW only mode),
                // unless we first pause and resume Open Kamera.
                // Update: on Galaxy S10e with Android 11 at least, no longer seem to have problems, but leave
                // the check for isRaw just in case for older devices.
                if (MyDebug.LOG) Log.d(TAG, "try reviewAction")
                try {
                    val intent = Intent(reviewAction, uri)
                    this.startActivity(intent)
                    done = true
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                }
            }
            if (!done) {
                if (MyDebug.LOG) Log.d(TAG, "try ACTION_VIEW")
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    this.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                    preview.showToast(null, R.string.no_gallery_app)
                } catch (e: SecurityException) {
                    // have received this crash from Google Play - don't display a toast, simply do nothing
                    Log.e(TAG, "SecurityException from ACTION_VIEW startActivity")
                    e.printStackTrace()
                }
            }
        }
    }

    /** Opens the Storage Access Framework dialog to select a folder for save location.
     * @param fromPreferences Whether called from the Preferences
     */
    fun openFolderChooserDialogSAF(fromPreferences: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "openFolderChooserDialogSAF: $fromPreferences"
        )
        this.safDialogFromPreferences = fromPreferences
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        //Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
        //intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, CHOOSE_SAVE_FOLDER_SAF_CODE)
    }

    /** Opens the Storage Access Framework dialog to select a file for ghost image.
     * @param fromPreferences Whether called from the Preferences
     */
    fun openGhostImageChooserDialogSAF(fromPreferences: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "openGhostImageChooserDialogSAF: $fromPreferences"
        )
        this.safDialogFromPreferences = fromPreferences
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        try {
            startActivityForResult(intent, CHOOSE_GHOST_IMAGE_SAF_CODE)
        } catch (e: ActivityNotFoundException) {
            preview.showToast(null, R.string.open_files_saf_exception_ghost)
            Log.e(TAG, "ActivityNotFoundException from startActivityForResult")
            e.printStackTrace()
        }
    }

    /** Opens the Storage Access Framework dialog to select a file for loading settings.
     * @param fromPreferences Whether called from the Preferences
     */
    fun openLoadSettingsChooserDialogSAF(fromPreferences: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "openLoadSettingsChooserDialogSAF: $fromPreferences"
        )
        this.safDialogFromPreferences = fromPreferences
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type =
            "text/xml" // note that application/xml doesn't work (can't select the xml files)!
        try {
            startActivityForResult(intent, CHOOSE_LOAD_SETTINGS_SAF_CODE)
        } catch (e: ActivityNotFoundException) {
            // see https://stackoverflow.com/questions/34021039/action-open-document-not-working-on-miui/34045627
            preview.showToast(null, R.string.open_files_saf_exception_generic)
            Log.e(TAG, "ActivityNotFoundException from startActivityForResult")
            e.printStackTrace()
        }
    }

    /** Call when the SAF save history has been updated.
     * This is only public so we can call from testing.
     * @param saveFolder The new SAF save folder Uri.
     */
    fun updateFolderHistorySAF(saveFolder: String) {
        if (MyDebug.LOG) Log.d(TAG, "updateSaveHistorySAF")
        if (saveLocationHistorySaf == null) {
            saveLocationHistorySaf =
                SaveLocationHistory(this, "save_location_history_saf", saveFolder)
        }
        saveLocationHistorySaf!!.updateFolderHistory(saveFolder, true)
    }

    /** Listens for the response from the Storage Access Framework dialog to select a folder
     * (as opened with openFolderChooserDialogSAF()).
     */
    @SuppressLint("WrongConstant")
    public override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onActivityResult: $requestCode"
        )

        super.onActivityResult(requestCode, resultCode, resultData)

        when (requestCode) {
            CHOOSE_SAVE_FOLDER_SAF_CODE -> {
                if (resultCode == RESULT_OK && resultData != null) {
                    val treeUri = resultData.data
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "returned treeUri: $treeUri"
                    )
                    // see https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions :
                    val takeFlags =
                        resultData.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    try {
                        /*if( true )
                        throw new SecurityException() // test*/
                        contentResolver.takePersistableUriPermission(treeUri!!, takeFlags)

                        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                        sharedPreferences.edit {
                            putString(
                                PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY,
                                treeUri.toString()
                            )
                        }

                        if (MyDebug.LOG) Log.d(TAG, "update folder history for saf")
                        updateFolderHistorySAF(treeUri.toString())

                        val file: String? = applicationInterface.storageUtils.imageFolderPath
                        if (file != null) {
                            preview.showToast(
                                null,
                                """
                                    ${resources.getString(R.string.changed_save_location)}
                                    $file
                                    """.trimIndent()
                            )
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException failed to take permission")
                        e.printStackTrace()
                        preview.showToast(null, R.string.saf_permission_failed)
                        // failed - if the user had yet to set a save location, make sure we switch SAF back off
                        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                        val uri = sharedPreferences.getString(
                            PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY,
                            ""
                        )!!
                        if (uri.isEmpty()) {
                            if (MyDebug.LOG) Log.d(TAG, "no SAF save location was set")
                            val editor = sharedPreferences.edit()
                            editor.putBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, false)
                            editor.apply()
                        }
                    }
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "SAF dialog cancelled")
                    // cancelled - if the user had yet to set a save location, make sure we switch SAF back off
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                    val uri =
                        sharedPreferences.getString(
                            PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY,
                            ""
                        )!!
                    if (uri.isEmpty()) {
                        if (MyDebug.LOG) Log.d(TAG, "no SAF save location was set")
                        val editor = sharedPreferences.edit()
                        editor.putBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, false)
                        editor.apply()
                        preview.showToast(null, R.string.saf_cancelled)
                    }
                }

                if (!safDialogFromPreferences) {
                    setWindowFlagsForCamera()
                    showPreview(true)
                }
            }

            CHOOSE_GHOST_IMAGE_SAF_CODE -> {
                if (resultCode == RESULT_OK && resultData != null) {
                    val fileUri = resultData.data
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "returned single fileUri: $fileUri"
                    )
                    // persist permission just in case?
                    val takeFlags = (resultData.flags
                            and (Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    try {
                        /*if( true )
                        throw new SecurityException() // test*/
                        // Check for the freshest data.
                        contentResolver.takePersistableUriPermission(fileUri!!, takeFlags)

                        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                        sharedPreferences.edit {
                            putString(
                                PreferenceKeys.GHOST_SELECTED_IMAGE_SAF_PREFERENCE_KEY,
                                fileUri.toString()
                            )
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException failed to take permission")
                        e.printStackTrace()
                        preview.showToast(null, R.string.saf_permission_failed_open_image)
                        // failed - if the user had yet to set a ghost image
                        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                        val uri = sharedPreferences.getString(
                            PreferenceKeys.GHOST_SELECTED_IMAGE_SAF_PREFERENCE_KEY,
                            ""
                        )!!
                        if (uri.isEmpty()) {
                            if (MyDebug.LOG) Log.d(TAG, "no SAF ghost image was set")
                            val editor = sharedPreferences.edit()
                            editor.putString(
                                PreferenceKeys.GHOST_IMAGE_PREFERENCE_KEY,
                                "preference_ghost_image_off"
                            )
                            editor.apply()
                        }
                    }
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "SAF dialog cancelled")
                    // cancelled - if the user had yet to set a ghost image, make sure we switch the option back off
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                    val uri = sharedPreferences.getString(
                        PreferenceKeys.GHOST_SELECTED_IMAGE_SAF_PREFERENCE_KEY,
                        ""
                    )!!
                    if (uri.isEmpty()) {
                        if (MyDebug.LOG) Log.d(TAG, "no SAF ghost image was set")
                        val editor = sharedPreferences.edit()
                        editor.putString(
                            PreferenceKeys.GHOST_IMAGE_PREFERENCE_KEY,
                            "preference_ghost_image_off"
                        )
                        editor.apply()
                    }
                }

                if (!safDialogFromPreferences) {
                    setWindowFlagsForCamera()
                    showPreview(true)
                }
            }

            CHOOSE_LOAD_SETTINGS_SAF_CODE -> {
                if (resultCode == RESULT_OK && resultData != null) {
                    val fileUri = resultData.data
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "returned single fileUri: $fileUri"
                    )
                    // persist permission just in case?
                    val takeFlags = (resultData.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    try {
                        /*if( true )
                        throw new SecurityException() // test*/
                        // Check for the freshest data.
                        contentResolver.takePersistableUriPermission(fileUri!!, takeFlags)

                        settingsManager.loadSettings(fileUri)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException failed to take permission")
                        e.printStackTrace()
                        preview.showToast(null, R.string.restore_settings_failed)
                    }
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "SAF dialog cancelled")
                }

                if (!safDialogFromPreferences) {
                    setWindowFlagsForCamera()
                    showPreview(true)
                }
            }
        }
    }

    /** Update the save folder (for non-SAF methods).
     */
    fun updateSaveFolder(newSaveLocation: String?) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "updateSaveFolder: $newSaveLocation"
        )
        if (newSaveLocation != null) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val origSaveLocation: String =
                applicationInterface.storageUtils.saveLocation

            if (origSaveLocation != newSaveLocation) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "changed save_folder to: " + applicationInterface.storageUtils
                        .saveLocation
                )
                sharedPreferences.edit {
                    putString(PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY, newSaveLocation)
                }

                saveLocationHistory.updateFolderHistory(storageUtils.saveLocation, true)
                val saveFolderName = getHumanReadableSaveFolder(
                    applicationInterface.storageUtils.saveLocation
                )
                preview.showToast(
                    null,
                    """
                        ${resources.getString(R.string.changed_save_location)}
                        $saveFolderName
                        """.trimIndent()
                )
            }
        }
    }

    class MyFolderChooserDialog : FolderChooserDialog() {
        override fun onDismiss(dialog: DialogInterface?) {
            if (MyDebug.LOG) Log.d(TAG, "FolderChooserDialog dismissed")
            // n.b., fragments have to be static (as they might be inserted into a new Activity - see http://stackoverflow.com/questions/15571010/fragment-inner-class-should-be-static),
            // so we access the MainActivity via the fragment's getActivity().
            val mainActivity = this.activity as MainActivity?
            // activity may be null, see https://stackoverflow.com/questions/13116104/best-practice-to-reference-the-parent-activity-of-a-fragment
            // have had Google Play crashes from this
            if (mainActivity != null) {
                mainActivity.setWindowFlagsForCamera()
                mainActivity.showPreview(true)
                val newSaveLocation: String? = this.chosenFolder
                mainActivity.updateSaveFolder(newSaveLocation)
            } else {
                if (MyDebug.LOG) Log.e(TAG, "activity no longer exists!")
            }
            super.onDismiss(dialog)
        }
    }

    /** Creates a dialog builder for specifying a save folder dialog (used when not using SAF,
     * and on scoped storage, as an alternative to using FolderChooserDialog).
     */
    fun createSaveFolderDialog(): AlertDialog.Builder {
        val alertDialog = AlertDialog.Builder(this)
        alertDialog.setTitle(R.string.preference_save_location)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.alertdialog_edittext, null)
        val editText = dialogView.findViewById<EditText>(R.id.edit_text)

        // set hint instead of content description for EditText, see https://support.google.com/accessibility/android/answer/6378120
        editText.hint = resources.getString(R.string.preference_save_location)
        editText.inputType = InputType.TYPE_CLASS_TEXT
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        editText.setText(
            sharedPreferences.getString(
                PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY,
                "OpenKamera"
            )
        )
        val filter: InputFilter = object : InputFilter {
            // whilst Android seems to allow any characters on internal memory, SD cards are typically formatted with FAT32
            val disallowed: String = "|\\?*<\":>"
            override fun filter(
                source: CharSequence,
                start: Int,
                end: Int,
                dest: Spanned,
                dstart: Int,
                dend: Int
            ): CharSequence? {
                for (i in start..<end) {
                    if (disallowed.indexOf(source[i]) != -1) {
                        return ""
                    }
                }
                // also check for '/', not allowed at start
                if (dstart == 0 && start < source.length && source[start] == '/') {
                    return ""
                }
                return null
            }
        }
        editText.filters = arrayOf(filter)

        alertDialog.setView(dialogView)

        alertDialog.setPositiveButton(
            android.R.string.ok
        ) { dialogInterface, i ->
            if (MyDebug.LOG) Log.d(
                TAG,
                "save location clicked okay"
            )
            var folder = editText.text.toString()
            folder = processUserSaveLocation(folder)
            updateSaveFolder(folder)
        }
        alertDialog.setNegativeButton(android.R.string.cancel, null)

        return alertDialog
    }

    /** Opens Open Kamera's own (non-Storage Access Framework) dialog to select a folder.
     */
    private fun openFolderChooserDialog() {
        if (MyDebug.LOG) Log.d(TAG, "openFolderChooserDialog")
        showPreview(false)
        setWindowFlagsForSettings()

        if (useScopedStorage()) {
            val alertDialog = createSaveFolderDialog()
            val alert = alertDialog.create()
            // AlertDialog.Builder.setOnDismissListener() requires API level 17, so do it this way instead
            alert.setOnDismissListener {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "save folder dialog dismissed"
                )
                setWindowFlagsForCamera()
                showPreview(true)
            }
            alert.show()
        } else {
            val startFolder: File = storageUtils.imageFolder

            val fragment: FolderChooserDialog = MyFolderChooserDialog()
            fragment.setStartFolder(startFolder)
            // use commitAllowingStateLoss() instead of fragment.show(), does to "java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState" crash seen on Google Play
            // see https://stackoverflow.com/questions/14262312/java-lang-illegalstateexception-can-not-perform-this-action-after-onsaveinstanc
            //fragment.show(getFragmentManager(), "FOLDER_FRAGMENT")
            fragmentManager.beginTransaction().add(fragment, "FOLDER_FRAGMENT")
                .commitAllowingStateLoss()
        }
    }

    /** Returns a human-readable string for the saveFolder (as stored in the preferences).
     */
    private fun getHumanReadableSaveFolder(saveFolder: String): String {
        var saveFolder = saveFolder
        if (applicationInterface.storageUtils.isUsingSAF) {
            // try to get human-readable form if possible
            val fileName: String? =
                applicationInterface.storageUtils.getFilePathFromDocumentUriSAF(
                    Uri.parse(saveFolder), true
                )
            if (fileName != null) {
                saveFolder = fileName
            }
        } else {
            // The strings can either be a sub-folder of DCIM, or (pre-scoped-storage) a full path, so normally either can be displayed.
            // But with scoped storage, an empty string is used to mean DCIM, so seems clearer to say that instead of displaying a blank line!
            if (useScopedStorage() && saveFolder.isEmpty()) {
                saveFolder = "DCIM"
            }
        }
        return saveFolder
    }

    /** User can long-click on gallery to select a recent save location from the history, of if not available,
     * go straight to the file dialog to pick a folder.
     */
    private fun longClickedGallery() {
        if (MyDebug.LOG) Log.d(TAG, "longClickedGallery")
        if (applicationInterface.storageUtils.isUsingSAF) {
            if (saveLocationHistorySaf == null || (saveLocationHistorySaf?.size()
                    ?: -1) <= 1
            ) {
                if (MyDebug.LOG) Log.d(TAG, "go straight to choose folder dialog for SAF")
                openFolderChooserDialogSAF(false)
                return
            }
        } else {
            if (saveLocationHistory.size() <= 1) {
                if (MyDebug.LOG) Log.d(TAG, "go straight to choose folder dialog")
                openFolderChooserDialog()
                return
            }
        }

        val history: SaveLocationHistory = if (applicationInterface.storageUtils.isUsingSAF)
            saveLocationHistorySaf!! else saveLocationHistory
        showPreview(false)
        val alertDialog = AlertDialog.Builder(this)
        alertDialog.setTitle(R.string.choose_save_location)
        val items = arrayOfNulls<CharSequence>(history.size() + 2)
        var index = 0
        // history is stored in order most-recent-last
        for (i in 0..<history.size()) {
            var folderName: String = history[history.size() - 1 - i]
            folderName = getHumanReadableSaveFolder(folderName)
            items[index++] = folderName
        }
        val clearIndex = index
        items[index++] = resources.getString(R.string.clear_folder_history)
        val newIndex = index
        items[index++] = resources.getString(R.string.choose_another_folder)
        //alertDialog.setItems(items, new DialogInterface.OnClickListener() {
        alertDialog.setSingleChoiceItems(
            items, 0
        ) { dialog, which ->
            if (which == clearIndex) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "selected clear save history"
                )
                AlertDialog.Builder(this@MainActivity)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(R.string.clear_folder_history)
                    .setMessage(R.string.clear_folder_history_question)
                    .setPositiveButton(
                        android.R.string.yes
                    ) { dialog, which ->
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "confirmed clear save history"
                        )
                        if (applicationInterface.storageUtils.isUsingSAF) clearFolderHistorySAF()
                        else clearFolderHistory()
                        setWindowFlagsForCamera()
                        showPreview(true)
                    }
                    .setNegativeButton(
                        android.R.string.no
                    ) { dialog, which ->
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "don't clear save history"
                        )
                        setWindowFlagsForCamera()
                        showPreview(true)
                    }
                    .setOnCancelListener {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "cancelled clear save history"
                        )
                        setWindowFlagsForCamera()
                        showPreview(true)
                    }
                    .show()
            } else if (which == newIndex) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "selected choose new folder"
                )
                if (applicationInterface.storageUtils.isUsingSAF) {
                    openFolderChooserDialogSAF(false)
                } else {
                    openFolderChooserDialog()
                }
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "selected: $which"
                )
                if (which >= 0 && which < history.size()) {
                    val saveFolder: String = history[history.size() - 1 - which]
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "changed save_folder from history to: $saveFolder"
                    )
                    val saveFolderName = getHumanReadableSaveFolder(saveFolder)
                    preview.showToast(
                        null,
                        """
                            ${resources.getString(R.string.changed_save_location)}
                            $saveFolderName
                            """.trimIndent()
                    )
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    val editor = sharedPreferences.edit()
                    if (applicationInterface.storageUtils.isUsingSAF) editor.putString(
                        PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY,
                        saveFolder
                    )
                    else editor.putString(PreferenceKeys.SAVE_LOCATION_PREFERENCE_KEY, saveFolder)
                    editor.apply()
                    history.updateFolderHistory(
                        saveFolder,
                        true
                    ) // to move new selection to most recent
                }
                setWindowFlagsForCamera()
                showPreview(true)
            }
            dialog.dismiss() // need to explicitly dismiss for setSingleChoiceItems
        }
        alertDialog.setOnCancelListener {
            setWindowFlagsForCamera()
            showPreview(true)
        }
        //getWindow().setLayout(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT)
        setWindowFlagsForSettings()
        showAlert(alertDialog.create())
    }

    /** Clears the non-SAF folder history.
     */
    fun clearFolderHistory() {
        if (MyDebug.LOG) Log.d(TAG, "clearFolderHistory")
        saveLocationHistory.clearFolderHistory(storageUtils.saveLocation)
    }

    /** Clears the SAF folder history.
     */
    fun clearFolderHistorySAF() {
        if (MyDebug.LOG) Log.d(TAG, "clearFolderHistorySAF")
        saveLocationHistorySaf?.clearFolderHistory(storageUtils.saveLocationSAF)
    }

    fun clickedShare(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedShare")
        applicationInterface.shareLastImage()
    }

    fun clickedTrash(view: View?) {
        if (MyDebug.LOG) Log.d(TAG, "clickedTrash")
        applicationInterface.trashLastImage()
    }

    /** User has pressed the take picture button, or done an equivalent action to request this (e.g.,
     * volume buttons, audio trigger).
     * @param photoSnapshot If true, then the user has requested taking a photo whilst video
     * recording. If false, either take a photo or start/stop video depending
     * on the current mode.
     */
    fun takePicture(photoSnapshot: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "takePicture")

        if (applicationInterface.photoMode === PhotoMode.Panorama) {
            if (preview.isTakingPhoto) {
                if (MyDebug.LOG) Log.d(TAG, "ignore whilst taking panorama photo")
            } else if (applicationInterface.gyroSensor.isRecording) {
                if (MyDebug.LOG) Log.d(TAG, "panorama complete")
                applicationInterface.finishPanorama()
                return
            } else if (!applicationInterface.canTakeNewPhoto()) {
                if (MyDebug.LOG) Log.d(TAG, "can't start new panoroma, still saving in background")
                // we need to test here, otherwise the Preview won't take a new photo - but we'll think we've
                // started the panorama!
            } else {
                if (MyDebug.LOG) Log.d(TAG, "start panorama")
                applicationInterface.startPanorama()
            }
        }

        this.takePicturePressed(photoSnapshot, false)
    }

    /** Returns whether the last photo operation was a continuous fast burst.
     */
    fun lastContinuousFastBurst(): Boolean {
        return this.lastContinuousFastBurst
    }

    /**
     * @param photoSnapshot If true, then the user has requested taking a photo whilst video
     * recording. If false, either take a photo or start/stop video depending
     * on the current mode.
     * @param continuousFastBurst If true, then start a continuous fast burst.
     */
    fun takePicturePressed(photoSnapshot: Boolean, continuousFastBurst: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "takePicturePressed")

        closePopup()

        this.lastContinuousFastBurst = continuousFastBurst
        preview.takePicturePressed(photoSnapshot, continuousFastBurst)
    }

    /** Lock the screen - this is Open Kamera's own lock to guard against accidental presses,
     * not the standard Android lock.
     */
    fun lockScreen() {
        findViewById<View>(R.id.locker).setOnTouchListener { arg0, event ->
            gestureDetector!!.onTouchEvent(event)
            //return true
        }
        isScreenLocked = true
        this.enableScreenLockOnBackPressedCallback(true) // also disable back button
    }

    /** Unlock the screen (see lockScreen()).
     */
    fun unlockScreen() {
        findViewById<View>(R.id.locker).setOnTouchListener(null)
        isScreenLocked = false
        this.enableScreenLockOnBackPressedCallback(false) // reenable back button
    }

    /** Listen for gestures.
     * Doing a swipe will unlock the screen (see lockScreen()).
     */
    private inner class MyGestureDetector : SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            try {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "from " + e1?.x + " , " + e1?.y + " to " + e2.x + " , " + e2.y
                )
                val vc = ViewConfiguration.get(this@MainActivity)
                //final int swipeMinDistance = 4*vc.getScaledPagingTouchSlop()
                val scale = resources.displayMetrics.density
                val swipeMinDistance = (160 * scale + 0.5f).toInt() // convert dps to pixels
                val swipeThresholdVelocity = vc.scaledMinimumFlingVelocity
                if (MyDebug.LOG) {
                    Log.d(TAG, "from " + e1?.x + " , " + e1?.y + " to " + e2.x + " , " + e2.y)
                    Log.d(
                        TAG,
                        "swipeMinDistance: $swipeMinDistance"
                    )
                }
                if (e1 != null) {
                    val xdist = e1.x - e2.x
                    val ydist = e1.y - e2.y
                    val dist2 = xdist * xdist + ydist * ydist
                    val vel2 = velocityX * velocityX + velocityY * velocityY
                    if (dist2 > swipeMinDistance * swipeMinDistance && vel2 > swipeThresholdVelocity * swipeThresholdVelocity) {
                        preview.showToast(screenLockedToast, R.string.unlocked)
                        unlockScreen()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        override fun onDown(e: MotionEvent): Boolean {
            preview.showToast(screenLockedToast, R.string.screen_is_locked)
            return true
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        if (MyDebug.LOG) Log.d(TAG, "onSaveInstanceState")
        super.onSaveInstanceState(state)
        if (::preview.isInitialized) {
            preview.onSaveInstanceState(state)
        }
        if (::applicationInterface.isInitialized) {
            applicationInterface.onSaveInstanceState(state)
        }
    }

    fun supportsExposureButton(): Boolean {
        if (preview.isVideoHighSpeed) {
            // manual ISO/exposure not supported for high speed video mode
            // it's safer not to allow opening the panel at all (otherwise the user could open it, and switch to manual)
            return false
        }
        if (applicationInterface.isCameraExtensionPref()) {
            // nothing in this UI (exposure compensation, manual ISO/exposure, manual white balance) is supported for camera extensions
            return false
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val isoValue = sharedPreferences.getString(
            PreferenceKeys.ISO_PREFERENCE_KEY,
            CameraController.ISO_DEFAULT
        )
        val manualIso = isoValue != CameraController.ISO_DEFAULT
        return preview.supportsExposures() || (manualIso && preview.supportsISORange())
    }

    fun cameraSetup() {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "cameraSetup")
            debugTime = System.currentTimeMillis()
        }
        if (preview.cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera controller is null")
            return
        }

        val oldWantNoLimits = wantNoLimits
        this.wantNoLimits = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
            if (MyDebug.LOG) Log.d(TAG, "multi-window mode")
            // don't support wantNoLimits mode in multi-window mode - extra complexity that the
            // preview size could change from simply resizing the window also problem that the
            // navigationGap, and whether we'd want wantNoLimits, can both change depending on
            // device orientation (because application can e.g. be in landscape mode even if device
            // has switched to portrait)
        } else if (setWindowInsetsListener && !edgeToEdgeMode) {
            val displaySize = Point()
            applicationInterface.getDisplaySize(displaySize, true)
            val displayWidth = max(displaySize.x.toDouble(), displaySize.y.toDouble()).toInt()
            val displayHeight = min(displaySize.x.toDouble(), displaySize.y.toDouble()).toInt()
            val displayAspectRatio = (displayWidth.toDouble()) / displayHeight
            val previewAspectRatio: Double = preview.currentPreviewAspectRatio
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "display_aspect_ratio: $displayAspectRatio"
                )
                Log.d(
                    TAG,
                    "preview_aspect_ratio: $previewAspectRatio"
                )
            }
            var previewIsWide = previewAspectRatio > displayAspectRatio + 1.0e-5f
            if (testPreviewWantNoLimits) {
                previewIsWide = testPreviewWantNoLimitsValue
            }
            if (previewIsWide) {
                if (MyDebug.LOG) Log.d(TAG, "preview is wide, set want_no_limits")
                this.wantNoLimits = true

                if (!oldWantNoLimits) {
                    if (MyDebug.LOG) Log.d(TAG, "need to change to FLAG_LAYOUT_NO_LIMITS")
                    // Ideally we'd just go straight to FLAG_LAYOUT_NO_LIMITS mode, but then all calls to onApplyWindowInsets()
                    // end up returning a value of 0 for the navigationGap! So we need to wait until we know the navigationGap.
                    if (_navigationGap != 0) {
                        // already have navigation gap, can go straight into no limits mode
                        if (MyDebug.LOG) Log.d(TAG, "set FLAG_LAYOUT_NO_LIMITS")
                        showUnderNavigation(true)
                        // need to layout the UI again due to now taking the navigation gap into account
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "layout UI due to changing want_no_limits behaviour"
                        )
                        mainUI.layoutUI()
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "but navigation_gap is 0")
                    }
                }
            } else if (oldWantNoLimits && _navigationGap != 0) {
                if (MyDebug.LOG) Log.d(TAG, "clear FLAG_LAYOUT_NO_LIMITS")
                showUnderNavigation(false)
                // need to layout the UI again due to no longer taking the navigation gap into account
                if (MyDebug.LOG) Log.d(TAG, "layout UI due to changing want_no_limits behaviour")
                mainUI.layoutUI()
            }
        }

        if (this.supportsForceVideo4K() && preview.usingCamera2API()) {
            if (MyDebug.LOG) Log.d(TAG, "using Camera2 API, so can disable the force 4K option")
            this.disableForceVideo4K()
        }
        if (this.supportsForceVideo4K() && preview.videoQualityHander.supportedVideoSizes.isNotEmpty()) {
            for (size in preview.videoQualityHander.supportedVideoSizes) {
                if (size.width >= 3840 && size.height >= 2160) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "camera natively supports 4K, so can disable the force option"
                    )
                    this.disableForceVideo4K()
                }
            }
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraSetup: time after handling Force 4K option: " + (System.currentTimeMillis() - debugTime)
        )

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        run {
            if (MyDebug.LOG) Log.d(TAG, "set up zoom")
            if (MyDebug.LOG) Log.d(TAG, "has_zoom? " + preview.supportsZoom())
            val zoomSeekBar = findViewById<SeekBar>(R.id.zoom_seekbar)

            if (preview.supportsZoom()) {
                setZoomSeekbar()

                if (sharedPreferences.getBoolean(
                        PreferenceKeys.SHOW_ZOOM_SLIDER_CONTROLS_PREFERENCE_KEY,
                        true
                    )
                ) {
                    if (!mainUI.inImmersiveMode()) {
                        zoomSeekBar.visibility = View.VISIBLE
                    }
                } else {
                    zoomSeekBar.visibility =
                        View.INVISIBLE // should be INVISIBLE not GONE, as the focusSeekbar is aligned to be left to this in future we might want this similarly for exposure panel
                }
            } else {
                zoomSeekBar.visibility =
                    View.INVISIBLE // should be INVISIBLE not GONE, as the focusSeekbar is aligned to be left to this in future we might want this similarly for the exposure panel
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "cameraSetup: time after setting up zoom: " + (System.currentTimeMillis() - debugTime)
            )

            val takePhotoButton = findViewById<View>(R.id.take_photo)
            if (sharedPreferences.getBoolean(PreferenceKeys.SHOW_TAKE_PHOTO_PREFERENCE_KEY, true)) {
                if (!mainUI.inImmersiveMode()) {
                    takePhotoButton.visibility = View.VISIBLE
                }
            } else {
                takePhotoButton.visibility = View.INVISIBLE
            }
        }
        run {
            if (MyDebug.LOG) Log.d(TAG, "set up manual focus")
            setManualFocusSeekbar(false)
            setManualFocusSeekbar(true)
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraSetup: time after setting up manual focus: " + (System.currentTimeMillis() - debugTime)
        )
        run {
            if (preview.supportsISORange()) {
                if (MyDebug.LOG) Log.d(TAG, "set up iso")
                val isoSeekBar = findViewById<SeekBar>(R.id.iso_seekbar)
                isoSeekBar.setOnSeekBarChangeListener(null) // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                //setProgressSeekbarExponential(isoSeekBar, preview.minimumISO, preview.maximumISO, preview.cameraController.getISO())
                manualSeekbars.setProgressSeekbarISO(
                    isoSeekBar,
                    preview.minimumISO.toLong(),
                    preview.maximumISO.toLong(),
                    preview.cameraController!!.iSO.toLong()
                )
                isoSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                    private var lastHapticTime: Long = 0

                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "iso seekbar onProgressChanged: $progress"
                        )
                        /*double frac = progress/(double)iso_seek_bar.getMax()
                        if( MyDebug.LOG )
                            Log.d(TAG, "exposureTime frac: " + frac)
                        double scaling = MainActivity.seekbarScaling(frac)
                        if( MyDebug.LOG )
                            Log.d(TAG, "exposureTime scaling: " + scaling)
                        int minIso = preview.minimumISO
                        int maxIso = preview.maximumISO
                        int iso = minIso + (int)(scaling * (maxIso - minIso))*/
                        /*int minIso = preview.minimumISO
                        int maxIso = preview.maximumISO
                        int iso = (int)exponentialScaling(frac, minIso, maxIso)*/
                        // n.b., important to update even if fromUser==false (e.g., so this works when user changes ISO via clicking
                        // the ISO buttons rather than moving the slider directly, see MainUI.setupExposureUI())
                        preview.setISO(manualSeekbars.getISO(progress))
                        mainUI.updateSelectedISOButton()
                        if (fromUser) {
                            lastHapticTime = performHapticFeedback(seekBar, lastHapticTime)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                    }
                })
                if (preview.supportsExposureTime()) {
                    if (MyDebug.LOG) Log.d(TAG, "set up exposure time")
                    val exposureTimeSeekBar = findViewById<SeekBar>(R.id.exposure_time_seekbar)
                    exposureTimeSeekBar.setOnSeekBarChangeListener(null) // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
                    //setProgressSeekbarExponential(exposureTimeSeekBar, preview.minimumExposureTime, preview.maximumExposureTime, preview.cameraController.getExposureTime())
                    manualSeekbars.setProgressSeekbarShutterSpeed(
                        exposureTimeSeekBar,
                        preview.minimumExposureTime,
                        preview.maximumExposureTime,
                        preview.cameraController!!.exposureTime
                    )
                    exposureTimeSeekBar.setOnSeekBarChangeListener(object :
                        OnSeekBarChangeListener {
                        private var lastHapticTime: Long = 0

                        override fun onProgressChanged(
                            seekBar: SeekBar,
                            progress: Int,
                            fromUser: Boolean
                        ) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "exposure_time seekbar onProgressChanged: $progress"
                            )
                            /*double frac = progress/(double)exposure_time_seek_bar.getMax()
                            if( MyDebug.LOG )
                                Log.d(TAG, "exposureTime frac: " + frac)
                            long minExposureTime = preview.minimumExposureTime
                            long maxExposureTime = preview.maximumExposureTime
                            long exposureTime = exponentialScaling(frac, minExposureTime, maxExposureTime)*/
                            preview.setExposureTime(manualSeekbars.getExposureTime(progress))
                            if (fromUser) {
                                lastHapticTime = performHapticFeedback(seekBar, lastHapticTime)
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar) {
                        }

                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                        }
                    })
                }
            }
        }
        setManualWBSeekbar()
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraSetup: time after setting up iso: " + (System.currentTimeMillis() - debugTime)
        )
        run {
            exposureSeekbarValues = null
            if (preview.supportsExposures()) {
                if (MyDebug.LOG) Log.d(TAG, "set up exposure compensation")
                val minExposure: Int = preview.minimumExposure
                val exposureSeekBar = findViewById<SeekBar>(R.id.exposure_seekbar)
                exposureSeekBar.setOnSeekBarChangeListener(null) // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state

                val exposureSeekbarNRepeatedZero =
                    3 // how many times to repeat 0 for R.id.exposure_seekbar, so that it "sticks" to zero when changing seekbar

                //exposure_seek_bar.setMax( preview.maximumExposure - minExposure + exposureSeekbarNRepeatedZero-1 )
                //exposure_seek_bar.setProgress( preview.currentExposure - minExposure )
                exposureSeekbarValues = ArrayList()
                val currentExposure: Int = preview.currentExposure
                var currentProgress = 0
                for (i in minExposure..preview.maximumExposure) {
                    exposureSeekbarValues!!.add(i)
                    if (i == 0) {
                        exposureSeekbarProgressZero = exposureSeekbarValues!!.size - 1
                        exposureSeekbarProgressZero += (exposureSeekbarNRepeatedZero - 1) / 2 // centre within the region of zeroes
                        for (j in 0..<exposureSeekbarNRepeatedZero - 1) {
                            exposureSeekbarValues!!.add(i)
                        }
                    }
                    if (i == currentExposure) {
                        if (i == 0) {
                            currentProgress += exposureSeekbarProgressZero
                        } else {
                            currentProgress = exposureSeekbarValues!!.size - 1
                        }
                    }
                }
                exposureSeekBar.max = exposureSeekbarValues!!.size - 1
                exposureSeekBar.progress = currentProgress
                exposureSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                    private var lastHapticTime: Long = 0

                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "exposure seekbar onProgressChanged: $progress"
                        )
                        if (exposureSeekbarValues == null) {
                            Log.e(TAG, "exposure_seekbar_values is null")
                            return
                        }
                        val newExposure = getExposureSeekbarValue(progress)
                        if (fromUser) {
                            // check if not scrolling past the repeated zeroes
                            if (preview.currentExposure !== newExposure) {
                                lastHapticTime = performHapticFeedback(seekBar, lastHapticTime)
                            }
                        }
                        cameraViewModel.onEvent(CameraUiEvent.OnExposureStepChanged(newExposure))
                        preview.setExposure(newExposure)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                    }
                })
            }
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraSetup: time after setting up exposure: " + (System.currentTimeMillis() - debugTime)
        )

        // On-screen icons such as exposure lock, white balance lock, face detection etc are made visible if necessary in
        // MainUI.showGUI()
        // However still need to enable visibility of icons where visibility depends on camera setup - e.g., exposure button
        // not supported for high speed video frame rates - see testTakeVideoFPSHighSpeedManual().
        // (Disabling is done in checkDisableGUIIcons(), called below.)
        val exposureButton = findViewById<View>(R.id.exposure)
        //exposureButton.setVisibility(supportsExposureButton() && !mainUI.inImmersiveMode() ? View.VISIBLE : View.GONE)
        if (supportsExposureButton() && !mainUI.inImmersiveMode()) exposureButton.visibility =
            View.VISIBLE

        // needed as availability of some icons is per-camera (e.g., flash, RAW)
        // for making icons visible, this is done elsewhere in call to MainUI.showGUI()
        if (checkDisableGUIIcons()) {
            if (MyDebug.LOG) Log.d(TAG, "cameraSetup: need to layoutUI as we hid some icons")
            mainUI.layoutUI()
        }

        // need to update some icons, e.g., white balance and exposure lock due to them being turned off when pause/resuming
        mainUI.updateOnScreenIcons()

        mainUI.setPopupIcon() // needed so that the icon is set right even if no flash mode is set when starting up camera (e.g., switching to front camera with no flash)
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraSetup: time after setting popup icon: " + (System.currentTimeMillis() - debugTime)
        )

        mainUI.setTakePhotoIcon()
        mainUI.setSwitchCameraContentDescription()
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraSetup: time after setting take photo icon: " + (System.currentTimeMillis() - debugTime)
        )

        if (!blockStartupToast) {
            this.showPhotoVideoToast(false)
        }
        blockStartupToast = false
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraSetup: total time for cameraSetup: " + (System.currentTimeMillis() - debugTime)
        )

        applicationInterface.drawPreview.setDimPreview(false)

        if (pushSwitchedCamera) {
            pushSwitchedCamera = false
            val switchCameraButton = findViewById<View>(R.id.switch_camera)
            switchCameraButton.animate().rotationBy(180f).setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }

    /** Sets up the zoom seekbar based on available zoom values.
     */
    fun setZoomSeekbar() {
        if (preview.cameraController == null) {
            // just in case - have seen rare NullPointerException crashes from Google Play
            return
        }
        val zoomSeekBar: SeekBar = findViewById(R.id.zoom_seekbar)
        zoomSeekBar.setOnSeekBarChangeListener(null) // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
        zoomSeekBar.max = preview.maxZoom
        zoomSeekBar.progress = preview.maxZoom - preview.cameraController!!.zoom
        zoomSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            private var lastHapticTime: Long = 0

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (MyDebug.LOG) Log.d(TAG, "zoom onProgressChanged: $progress")
                // note we zoom even if !fromUser, as various other UI controls (multitouch, volume key zoom)
                // indirectly set zoom via this method, from setting the zoom slider
                // if hasSmoothZoom()==true, then the preview already handled zooming to the current value
                if (!preview.hasSmoothZoom()) {
                    val newZoomFactor = preview.maxZoom - progress
                    if (fromUser && preview.cameraController != null) {
                        val oldZoomRatio = preview.zoomRatio
                        val newZoomRatio = preview.getZoomRatio(newZoomFactor)
                        if (newZoomRatio != oldZoomRatio) {
                            lastHapticTime = performHapticFeedback(seekBar, lastHapticTime)
                        }
                    }
                    cameraViewModel.onEvent(
                        CameraUiEvent.OnZoomChanged(
                            preview.getZoomRatio(
                                newZoomFactor
                            )
                        )
                    )
                    preview.zoomTo(
                        newZoomFactor = newZoomFactor,
                        allowSmoothZoom = false,
                        allowZoomTransition = true
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    fun setManualFocusSeekbarProgress(isTargetDistance: Boolean, focusDistance: Float) {
        val focusSeekBar =
            findViewById<SeekBar>(if (isTargetDistance) R.id.focus_bracketing_target_seekbar else R.id.focus_seekbar)
        ManualSeekbars.setProgressSeekbarScaled(
            focusSeekBar,
            0.0,
            preview.minimumFocusDistance.toDouble(),
            focusDistance.toDouble()
        )
    }

    private fun setManualFocusSeekbar(isTargetDistance: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "setManualFocusSeekbar")
        val focusSeekBar =
            findViewById<SeekBar>(if (isTargetDistance) R.id.focus_bracketing_target_seekbar else R.id.focus_seekbar)
        focusSeekBar.setOnSeekBarChangeListener(null) // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
        setManualFocusSeekbarProgress(
            isTargetDistance,
            if (isTargetDistance) preview.cameraController!!.focusBracketingTargetDistance else preview.cameraController!!.focusDistance
        )
        focusSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            private var hasSavedZoom = false
            private var savedZoomFactor = 0

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!isTargetDistance && applicationInterface.isFocusBracketingSourceAutoPref()) {
                    // source is set from continuous focus, not by changing the seekbar
                    if (fromUser) {
                        // but if user has manually changed, then exit auto mode
                        applicationInterface.setFocusBracketingSourceAutoPref(false)
                        mainUI.destroyPopup() // need to recreate popup
                    } else {
                        return
                    }
                }
                val frac = progress / focusSeekBar.max.toDouble()
                val scaling: Double = ManualSeekbars.seekbarScaling(frac)
                val focusDistance = (scaling * preview.minimumFocusDistance).toFloat()
                preview.setFocusDistance(focusDistance, isTargetDistance, true)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                if (MyDebug.LOG) Log.d(TAG, "manual focus seekbar: onStartTrackingTouch")
                hasSavedZoom = false
                if (preview.supportsZoom()) {
                    val focusAssist: Float = applicationInterface.focusAssistPref.toFloat()
                    if (focusAssist > 0 && preview.cameraController != null) {
                        hasSavedZoom = true
                        savedZoomFactor = preview.cameraController!!.zoom
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "zoom by $focusAssist for focus assist, zoom factor was: $savedZoomFactor"
                        )
                        val newZoomFactor: Int = preview.getScaledZoomFactor(focusAssist)
                        preview.cameraController!!.zoom = newZoomFactor
                    }
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (MyDebug.LOG) Log.d(TAG, "manual focus seekbar: onStopTrackingTouch")
                if (hasSavedZoom && preview.cameraController != null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "unzoom for focus assist, zoom factor was: $savedZoomFactor"
                    )
                    preview.cameraController!!.zoom = savedZoomFactor
                }
                preview.stoppedSettingFocusDistance(isTargetDistance)
            }
        })
        setManualFocusSeekBarVisibility(isTargetDistance)
    }

    fun showManualFocusSeekbar(isTargetDistance: Boolean): Boolean {
        if ((applicationInterface.photoMode === PhotoMode.FocusBracketing) && !preview.isVideo) {
            return true // both seekbars shown in focus bracketing mode
        }
        if (isTargetDistance) {
            return false // target seekbar only shown in focus bracketing mode
        }
        val isVisible =
            preview.currentFocusValue != null && preview.currentFocusValue.equals("focus_mode_manual2")
        return isVisible
    }

    fun setManualFocusSeekBarVisibility(isTargetDistance: Boolean) {
        val isVisible = showManualFocusSeekbar(isTargetDistance)
        val focusSeekBar =
            findViewById<SeekBar>(if (isTargetDistance) R.id.focus_bracketing_target_seekbar else R.id.focus_seekbar)
        val visibility = if (isVisible) View.VISIBLE else View.GONE
        focusSeekBar.visibility = visibility
        if (isVisible) {
            applicationInterface.drawPreview
                .updateSettings() // needed so that we reset focusSeekbarsMarginLeft, as the focus seekbars can only be updated when visible
        }
    }

    fun setManualWBSeekbar() {
        if (MyDebug.LOG) Log.d(TAG, "setManualWBSeekbar")
        if (preview.supportedWhiteBalances.isNotEmpty() && preview.supportsWhiteBalanceTemperature()) {
            if (MyDebug.LOG) Log.d(TAG, "set up manual white balance")
            val whiteBalanceSeekBar = findViewById<SeekBar>(R.id.white_balance_seekbar)
            whiteBalanceSeekBar.setOnSeekBarChangeListener(null) // clear an existing listener - don't want to call the listener when setting up the progress bar to match the existing state
            val minimumTemperature: Long = preview.minimumWhiteBalanceTemperature.toLong()
            val maximumTemperature: Long = preview.maximumWhiteBalanceTemperature.toLong()
            /*
            // white balance should use linear scaling
            white_balance_seek_bar.setMax(maximumTemperature - minimumTemperature)
            white_balance_seek_bar.setProgress(preview.cameraController.getWhiteBalanceTemperature() - minimumTemperature)
            */
            manualSeekbars.setProgressSeekbarWhiteBalance(
                whiteBalanceSeekBar,
                minimumTemperature,
                maximumTemperature,
                preview.cameraController!!.whiteBalanceTemperature.toLong()
            )
            whiteBalanceSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                private var lastHapticTime: Long = 0

                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "white balance seekbar onProgressChanged: $progress"
                    )
                    //int temperature = minimumTemperature + progress
                    //preview.setWhiteBalanceTemperature(temperature)
                    preview.setWhiteBalanceTemperature(
                        manualSeekbars.getWhiteBalanceTemperature(
                            progress
                        )
                    )
                    if (fromUser) {
                        lastHapticTime = performHapticFeedback(seekBar, lastHapticTime)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                }
            })
        }
    }

    fun supportsAutoStabilise(): Boolean {
        if (applicationInterface.isRawOnly) return false // if not saving JPEGs, no point having auto-stabilise mode, as it won't affect the RAW images

        if (applicationInterface.photoMode === PhotoMode.Panorama) return false // not supported in panorama mode

        return this.supportsAutoStabilise
    }

    /** Returns whether the device supports auto-level at all. Most callers probably want to use
     * supportsAutoStabilise() which also checks whether auto-level is allowed with current options.
     */
    fun deviceSupportsAutoStabilise(): Boolean {
        return this.supportsAutoStabilise
    }

    fun supportsDRO(): Boolean {
        if (applicationInterface.isRawOnly(PhotoMode.DRO)) return false // if not saving JPEGs, no point having DRO mode, as it won't affect the RAW images

        // require at least Android 5, for the Renderscript support in HDRProcessor
        return true
    }

    fun supportsHDR(): Boolean {
        // we also require the device have sufficient memory to do the processing
        // also require at least Android 5, for the Renderscript support in HDRProcessor
        return largeHeapMemory >= 128 && preview.supportsExpoBracketing()
    }

    fun supportsExpoBracketing(): Boolean {
        if (applicationInterface.isImageCaptureIntent) return false // don't support expo bracketing mode if called from image capture intent

        return preview.supportsExpoBracketing()
    }

    fun supportsFocusBracketing(): Boolean {
        if (applicationInterface.isImageCaptureIntent) return false // don't support focus bracketing mode if called from image capture intent

        return preview.supportsFocusBracketing()
    }

    /** Whether we support the auto mode for setting source focus distance for focus bracketing mode.
     * Note the caller should still separately call supportsFocusBracketing() to see if focus
     * bracketing is supported in the first place.
     */
    fun supportsFocusBracketingSourceAuto(): Boolean {
        return preview.supportsFocus() && (preview.supportedFocusValues?.contains("focus_mode_continuous_picture") == true)
    }

    fun supportsPanorama(): Boolean {
        // don't support panorama mode if called from image capture intent
        // in theory this works, but problem that currently we'd end up doing the processing on the UI thread, so risk ANR
        if (applicationInterface.isImageCaptureIntent) return false
        // require 256MB just to be safe, due to the large number of images that may be created
        // also require at least Android 5, for Renderscript
        // remember to update the FAQ "Why isn't Panorama supported on my device?" if this changes
        return largeHeapMemory >= 256 && applicationInterface.gyroSensor.hasSensors()
        //return false // currently blocked for release
    }

    fun supportsFastBurst(): Boolean {
        if (applicationInterface.isImageCaptureIntent) return false // don't support burst mode if called from image capture intent

        // require 512MB just to be safe, due to the large number of images that may be created
        return (preview.usingCamera2API() && largeHeapMemory >= 512 && preview.supportsBurst())
    }

    fun supportsNoiseReduction(): Boolean {
        // require at least Android 5, for the Renderscript support in HDRProcessor, but we require
        // Android 7 to limit to more modern devices (for performance reasons)
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && preview.usingCamera2API() && largeHeapMemory >= 512 && preview.supportsBurst() && preview.supportsExposureTime())
        //return false // currently blocked for release
    }

    /** Whether the Camera vendor extension is supported (see
     * https://developer.android.com/reference/android/hardware/camera2/CameraExtensionCharacteristics ).
     */
    fun supportsCameraExtension(extension: Int): Boolean {
        return preview.supportsCameraExtension(extension)
    }

    /** Whether RAW mode would be supported for various burst modes (expo bracketing etc).
     * Note that caller should still separately check preview.supportsRaw() if required.
     */
    fun supportsBurstRaw(): Boolean {
        return (largeHeapMemory >= 512)
    }

    fun supportsOptimiseFocusLatency(): Boolean {
        // whether to support optimising focus for latency
        // in theory this works on any device, as well as old or Camera2 API, but restricting this for now to avoid risk of poor default behaviour
        // on older devices
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && preview.usingCamera2API())
    }

    fun supportsPreviewBitmaps(): Boolean {
        // In practice, we only use TextureView on Android 5+ (with Camera2 API enabled) anyway, but have put an explicit check here -
        // even if in future we allow TextureView pre-Android 5, we still need Android 5+ for Renderscript.
        return preview.view is TextureView && largeHeapMemory >= 128
    }

    fun supportsPreShots(): Boolean {
        // Need at least Android 5+ for TextureView
        // Need at least Android 8+ for video encoding classes
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && preview.view is TextureView && largeHeapMemory >= 512
    }

    private fun maxExpoBracketingNImages(): Int {
        return preview.maxExpoBracketingNImages()
    }

    fun supportsForceVideo4K(): Boolean {
        return this.supportsForceVideo4k
    }

    fun supportsCamera2(): Boolean {
        return this.supportsCamera2
    }

    private fun disableForceVideo4K() {
        this.supportsForceVideo4k = false
    }

    val locationSupplier: LocationSupplier
        get() = applicationInterface.locationSupplier

    val storageUtils: StorageUtils
        get() = applicationInterface.storageUtils

    val imageFolder: File
        get() = applicationInterface.storageUtils.imageFolder

    val changedAutoStabiliseToastBoxer: ToastBoxer
        get() = changedAutoStabiliseToast

    private fun getPhotoModeString(
        photoMode: PhotoMode,
        stringForStd: Boolean
    ): String? {
        var photoModeString: String? = null
        when (photoMode) {
            PhotoMode.Standard -> if (stringForStd) photoModeString =
                resources.getString(R.string.photo_mode_standard_full)

            PhotoMode.DRO -> photoModeString = resources.getString(R.string.photo_mode_dro)
            PhotoMode.HDR -> photoModeString = resources.getString(R.string.photo_mode_hdr)
            PhotoMode.ExpoBracketing -> photoModeString =
                resources.getString(R.string.photo_mode_expo_bracketing_full)

            PhotoMode.FocusBracketing -> {
                photoModeString = resources.getString(R.string.photo_mode_focus_bracketing_full)
                val nImages: Int = applicationInterface.getFocusBracketingNImagesPref()
                photoModeString += " ($nImages)"
            }

            PhotoMode.FastBurst -> {
                photoModeString = resources.getString(R.string.photo_mode_fast_burst_full)
                val nImages: Int = applicationInterface.getBurstNImages()
                photoModeString += " ($nImages)"
            }

            PhotoMode.NoiseReduction -> photoModeString =
                resources.getString(R.string.photo_mode_noise_reduction_full)

            PhotoMode.Panorama -> photoModeString =
                resources.getString(R.string.photo_mode_panorama_full)

            PhotoMode.X_Auto -> photoModeString =
                resources.getString(R.string.photo_mode_x_auto_full)

            PhotoMode.X_HDR -> photoModeString =
                resources.getString(R.string.photo_mode_x_hdr_full)

            PhotoMode.X_Night -> photoModeString =
                resources.getString(R.string.photo_mode_x_night_full)

            PhotoMode.X_Bokeh -> photoModeString =
                resources.getString(R.string.photo_mode_x_bokeh_full)

            PhotoMode.X_Beauty -> photoModeString =
                resources.getString(R.string.photo_mode_x_beauty_full)
        }
        return photoModeString
    }

    /** Displays a toast with information about the current preferences.
     * If alwaysShow is true, the toast is always displayed otherwise, we only display
     * a toast if it's important to notify the user (i.e., unusual non-default settings are
     * set). We want a balance between not pestering the user too much, whilst also reminding
     * them if certain settings are on.
     */
    private fun showPhotoVideoToast(alwaysShow: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "showPhotoVideoToast")
            Log.d(TAG, "always_show? $alwaysShow")
        }
        if (preview.cameraController == null || this.isCameraInBackground) {
            if (MyDebug.LOG) Log.d(TAG, "camera not open or in background")
            return
        }
        val cameraController: CameraController = preview.cameraController!!
        var toastString: String
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        var simple = true
        val videoHighSpeed: Boolean = preview.isVideoHighSpeed
        val photoMode: PhotoMode = applicationInterface.photoMode
        if (preview.isVideo) {
            val profile: VideoProfile = preview.videoProfile

            val extensionString: String = profile.fileExtension
            if (profile.fileExtension != "mp4") {
                simple = false
            }
            val bitrateString: String =
                if (profile.videoBitRate >= 10000000) (profile.videoBitRate / 1000000).toString() + "Mbps"
                else if (profile.videoBitRate >= 10000) (profile.videoBitRate / 1000).toString() + "Kbps"
                else "${profile.videoBitRate}bps"
            val bitrateValue: String = applicationInterface.getVideoBitratePref()
            if (bitrateValue != "default") {
                simple = false
            }

            val captureRate: Double = profile.videoCaptureRate
            val captureRateString =
                if (captureRate < 9.5f) DecimalFormat("#0.###").format(captureRate) else (profile.videoCaptureRate + 0.5).toInt()
                    .toString()
            toastString =
                """${resources.getString(R.string.video)}: ${profile.videoFrameWidth}x${profile.videoFrameHeight}
$captureRateString${resources.getString(R.string.fps)}${
                    if (videoHighSpeed) " [" + resources.getString(R.string.high_speed) + "]" else ""
                }, $bitrateString ($extensionString)"""

            val fpsValue: String = applicationInterface.getVideoFPSPref()
            if (fpsValue != "default" || videoHighSpeed) {
                simple = false
            }

            val captureRateFactor: Float = applicationInterface.getVideoCaptureRateFactor()
            if (abs((captureRateFactor - 1.0f).toDouble()) > 1.0e-5) {
                toastString += """
                    
                    ${resources.getString(R.string.preference_video_capture_rate)}: ${captureRateFactor}x
                    """.trimIndent()
                simple = false
            }

            run {
                val tonemapProfile: TonemapProfile =
                    applicationInterface.getVideoTonemapProfile()
                if (tonemapProfile !== TonemapProfile.TONEMAPPROFILE_OFF && preview.supportsTonemapCurve) {
                    if (applicationInterface.getVideoTonemapProfile() !== TonemapProfile.TONEMAPPROFILE_OFF && preview.supportsTonemapCurve) {
                        var stringId = 0
                        when (tonemapProfile) {
                            TonemapProfile.TONEMAPPROFILE_REC709 -> stringId =
                                R.string.preference_video_rec709

                            TonemapProfile.TONEMAPPROFILE_SRGB -> stringId =
                                R.string.preference_video_srgb

                            TonemapProfile.TONEMAPPROFILE_LOG -> stringId = R.string.video_log
                            TonemapProfile.TONEMAPPROFILE_GAMMA -> stringId =
                                R.string.preference_video_gamma

                            TonemapProfile.TONEMAPPROFILE_JTVIDEO -> stringId =
                                R.string.preference_video_jtvideo

                            TonemapProfile.TONEMAPPROFILE_JTLOG -> stringId =
                                R.string.preference_video_jtlog

                            TonemapProfile.TONEMAPPROFILE_JTLOG2 -> stringId =
                                R.string.preference_video_jtlog2

                            else -> {}
                        }
                        if (stringId != 0) {
                            simple = false
                            toastString += """
                            
                            ${resources.getString(stringId)}
                            """.trimIndent()
                            if (tonemapProfile === TonemapProfile.TONEMAPPROFILE_GAMMA) {
                                toastString += " " + applicationInterface.getVideoProfileGamma()
                            }
                        } else {
                            Log.e(
                                TAG,
                                "unknown tonemap_profile: $tonemapProfile"
                            )
                        }
                    }
                }
            }

            val recordAudio: Boolean = applicationInterface.getRecordAudioPref()
            if (!recordAudio) {
                toastString += """
                    
                    ${resources.getString(R.string.audio_disabled)}
                    """.trimIndent()
                simple = false
            }
            val maxDurationValue =
                sharedPreferences.getString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "0")!!
            if (maxDurationValue.isNotEmpty() && maxDurationValue != "0") {
                val entriesArray =
                    resources.getStringArray(R.array.preference_video_max_duration_entries)
                val valuesArray =
                    resources.getStringArray(R.array.preference_video_max_duration_values)
                val index = listOf(*valuesArray).indexOf(maxDurationValue)
                if (index != -1) { // just in case!
                    val entry = entriesArray[index]
                    toastString += """
                        
                        ${resources.getString(R.string.max_duration)}: $entry
                        """.trimIndent()
                    simple = false
                }
            }
            val maxFilesize: Long = applicationInterface.videoMaxFileSizeUserPref
            if (maxFilesize != 0L) {
                toastString += """
                    
                    ${resources.getString(R.string.max_filesize)}: 
                    """.trimIndent()
                if (maxFilesize >= 1024 * 1024 * 1024) {
                    val maxFilesizeGb = maxFilesize / (1024 * 1024 * 1024)
                    toastString += maxFilesizeGb.toString() + resources.getString(R.string.gb_abbreviation)
                } else {
                    val maxFilesizeMb = maxFilesize / (1024 * 1024)
                    toastString += maxFilesizeMb.toString() + resources.getString(R.string.mb_abbreviation)
                }
                simple = false
            }
            if (applicationInterface.getVideoFlashPref() && preview.supportsFlash()) {
                toastString += """
                    
                    ${resources.getString(R.string.preference_video_flash)}
                    """.trimIndent()
                simple = false
            }
        } else {
            if (photoMode === PhotoMode.Panorama) {
                // don't show resolution in panorama mode
                toastString = ""
            } else {
                toastString = resources.getString(R.string.photo)
                val currentSize: CameraController.Size? = preview.currentPictureSize
                toastString += " " + currentSize?.width + "x" + currentSize?.height
            }

            val photoModeString = getPhotoModeString(photoMode, false)
            if (photoModeString != null) {
                toastString += (if (toastString.isEmpty()) "" else "\n") + resources.getString(R.string.photo_mode) + ": " + photoModeString
                if (photoMode !== PhotoMode.DRO && photoMode !== PhotoMode.HDR && photoMode !== PhotoMode.NoiseReduction) simple =
                    false
            }

            if (preview.supportsFocus() && preview.supportedFocusValues!!.size > 1 && photoMode !== PhotoMode.FocusBracketing
            ) {
                val focusValue: String? = preview.currentFocusValue
                if (focusValue != null && (focusValue != "focus_mode_auto") && (focusValue != "focus_mode_continuous_picture")) {
                    val focusEntry: String? = preview.findFocusEntryForValue(focusValue)
                    if (focusEntry != null) {
                        toastString += """
                            
                            $focusEntry
                            """.trimIndent()
                    }
                }
            }

            if (applicationInterface.autoStabilisePref) {
                // important as users are sometimes confused at the behaviour if they don't realise the option is on
                toastString += (if (toastString.isEmpty()) "" else "\n") + resources.getString(R.string.preference_auto_stabilise)
                simple = false
            }
        }
        if (applicationInterface.getFaceDetectionPref()) {
            // important so that the user realises why touching for focus/metering areas won't work - easy to forget that face detection has been turned on!
            toastString += """
                
                ${resources.getString(R.string.preference_face_detection)}
                """.trimIndent()
            simple = false
        }
        if (!videoHighSpeed) {
            //manual ISO only supported for high speed video
            val isoValue: String = applicationInterface.getISOPref()
            if (isoValue != CameraController.ISO_DEFAULT) {
                toastString += "\nISO: $isoValue"
                if (preview.supportsExposureTime()) {
                    val exposureTimeValue: Long = applicationInterface.getExposureTimePref()
                    toastString += " " + preview.getExposureTimeString(exposureTimeValue)
                }
                simple = false
            }
            val currentExposure: Int = cameraController.exposureCompensation
            if (currentExposure != 0) {
                toastString += """
                    
                    ${preview.getExposureCompensationString(currentExposure)}
                    """.trimIndent()
                simple = false
            }
        }
        try {
            val sceneMode: String? = cameraController.sceneMode
            val whiteBalance: String? = cameraController.whiteBalance
            val colorEffect: String? = cameraController.colorEffect
            if (sceneMode != null && sceneMode != CameraController.SCENE_MODE_DEFAULT) {
                toastString += """
                    
                    ${resources.getString(R.string.scene_mode)}: ${
                    mainUI.getEntryForSceneMode(
                        sceneMode
                    )
                }
                    """.trimIndent()
                simple = false
            }
            if (whiteBalance != null && whiteBalance != CameraController.WHITE_BALANCE_DEFAULT) {
                toastString += """
                    
                    ${resources.getString(R.string.white_balance)}: ${
                    mainUI.getEntryForWhiteBalance(
                        whiteBalance
                    )
                }
                    """.trimIndent()
                if (whiteBalance == "manual" && preview.supportsWhiteBalanceTemperature()) {
                    toastString += " " + cameraController.whiteBalanceTemperature
                }
                simple = false
            }
            if (colorEffect != null && colorEffect != CameraController.COLOR_EFFECT_DEFAULT) {
                toastString += """
                    
                    ${resources.getString(R.string.color_effect)}: ${
                    mainUI.getEntryForColorEffect(
                        colorEffect
                    )
                }
                    """.trimIndent()
                simple = false
            }
        } catch (e: RuntimeException) {
            // catch runtime error from cameraController old API from camera.getParameters()
            e.printStackTrace()
        }
        val lockOrientation: String = applicationInterface.getLockOrientationPref()
        if (lockOrientation != "none" && photoMode !== PhotoMode.Panorama) {
            // panorama locks to portrait, but don't want to display that in the toast
            val entriesArray =
                resources.getStringArray(R.array.preference_lock_orientation_entries)
            val valuesArray = resources.getStringArray(R.array.preference_lock_orientation_values)
            val index = listOf(*valuesArray).indexOf(lockOrientation)
            if (index != -1) { // just in case!
                val entry = entriesArray[index]
                toastString += """
                    
                    $entry
                    """.trimIndent()
                simple = false
            }
        }
        val timer = sharedPreferences.getString(PreferenceKeys.TIMER_PREFERENCE_KEY, "0")!!
        if (timer != "0" && photoMode !== PhotoMode.Panorama) {
            val entriesArray = resources.getStringArray(R.array.preference_timer_entries)
            val valuesArray = resources.getStringArray(R.array.preference_timer_values)
            val index = listOf(*valuesArray).indexOf(timer)
            if (index != -1) { // just in case!
                val entry = entriesArray[index]
                toastString += """
                    
                    ${resources.getString(R.string.preference_timer)}: $entry
                    """.trimIndent()
                simple = false
            }
        }
        val repeat: String = applicationInterface.getRepeatPref()
        if (repeat != "1") {
            val entriesArray = resources.getStringArray(R.array.preference_burst_mode_entries)
            val valuesArray = resources.getStringArray(R.array.preference_burst_mode_values)
            val index = listOf(*valuesArray).indexOf(repeat)
            if (index != -1) { // just in case!
                val entry = entriesArray[index]
                toastString += """
                    
                    ${resources.getString(R.string.preference_burst_mode)}: $entry
                    """.trimIndent()
                simple = false
            }
        }

        /*if( audioListener != null ) {
            toastString += "\n" + getResources().getString(R.string.preference_audio_noise_control)
        }*/
        if (MyDebug.LOG) {
            Log.d(TAG, "toast_string: $toastString")
            Log.d(TAG, "simple?: $simple")
            Log.d(
                TAG,
                "push_info_toast_text: $pushInfoToastText"
            )
        }
        val useFakeToast = true
        if (!simple || alwaysShow) {
            if (pushInfoToastText != null) {
                toastString = """
                    $pushInfoToastText
                    $toastString
                    """.trimIndent()
            }
            preview.showToast(switchVideoToast, toastString, useFakeToast)
        } else if (pushInfoToastText != null) {
            preview.showToast(switchVideoToast, pushInfoToastText, useFakeToast)
        }
        pushInfoToastText = null // reset
    }

    fun hasAudioListener(): Boolean {
        return audioListener != null
    }

    fun freeAudioListener(waitUntilDone: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "freeAudioListener")
        if (audioListener != null) {
            audioListener!!.release(waitUntilDone)
            audioListener = null
        }
        mainUI.audioControlStopped()
    }

    fun startAudioListener() {
        if (MyDebug.LOG) Log.d(TAG, "startAudioListener")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // we restrict the checks to Android 6 or later just in case, see note in LocationSupplier.setupLocationListener()
            if (MyDebug.LOG) Log.d(TAG, "check for record audio permission")
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (MyDebug.LOG) Log.d(TAG, "record audio permission not available")
                applicationInterface.requestRecordAudioPermission()
                return
            }
        }

        val callback = MyAudioTriggerListenerCallback(this)
        audioListener = AudioListener(callback)
        if (audioListener!!.status()) {
            preview.showToast(audioControlToast, R.string.audio_listener_started, true)

            audioListener!!.start()
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val sensitivityPref = sharedPreferences.getString(
                PreferenceKeys.AUDIO_NOISE_CONTROL_SENSITIVITY_PREFERENCE_KEY,
                "0"
            )!!
            val audioNoiseSensitivity = when (sensitivityPref) {
                "3" -> 50
                "2" -> 75
                "1" -> 125
                "-1" -> 150
                "-2" -> 200
                "-3" -> 400
                else ->                     // default
                    100
            }
            callback.setAudioNoiseSensitivity(audioNoiseSensitivity)
            mainUI.audioControlStarted()
        } else {
            audioListener!!.release(true) // shouldn't be needed, but just to be safe
            audioListener = null
            preview.showToast(null, R.string.audio_listener_failed)
        }
    }

    fun hasAudioControl(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val audioControl =
            sharedPreferences.getString(PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, "none")!!
        /*if( audio_control.equals("voice") ) {
            return speechControl.hasSpeechRecognition()
        }
        else*/
        return audioControl == "noise"
    }

    /*void startAudioListeners() {
        initAudioListener()
        // no need to restart speech recognizer, as we didn't free it in stopAudioListeners(), and it's controlled by a user button
    }*/
    fun stopAudioListeners() {
        freeAudioListener(true)
        /*if( speechControl.hasSpeechRecognition() ) {
            // no need to free the speech recognizer, just stop it
            speechControl.stopListening()
        }*/
    }

    fun initLocation() {
        if (MyDebug.LOG) Log.d(TAG, "initLocation")
        if (isAppPaused) {
            if (MyDebug.LOG) Log.d(TAG, "initLocation: app is paused!")
            // we shouldn't need this (as we only call initLocation() when active), but just in case we end up here after onPause...
            // in fact this happens when we need to grant permission for location - the call to initLocation() from
            // MainActivity.onRequestPermissionsResult()->PermissionsHandler.onRequestPermissionsResult() will be when the application
            // is still paused - so we won't do anything here, but instead initLocation() will be called after when resuming.
        } else if (isCameraInBackground) {
            if (MyDebug.LOG) Log.d(TAG, "initLocation: camera in background!")
            // we will end up here if app is pause/resumed when camera in background (settings, dialog, etc)
        } else if (!applicationInterface.locationSupplier.setupLocationListener()) {
            if (MyDebug.LOG) Log.d(TAG, "location permission not available, so request permission")
            permissionHandler.requestLocationPermission()
        }
    }

    private fun initGyroSensors() {
        if (MyDebug.LOG) Log.d(TAG, "initGyroSensors")
        if (applicationInterface.photoMode === PhotoMode.Panorama) {
            applicationInterface.gyroSensor.enableSensors()
        } else {
            applicationInterface.gyroSensor.disableSensors()
        }
    }

    fun speak(text: String?) {
        if (textToSpeech != null && textToSpeechSuccess) {
            textToSpeech!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onRequestPermissionsResult: requestCode $requestCode"
        )
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHandler.onRequestPermissionsResult(requestCode, grantResults)
    }

    fun restartOpenKamera() {
        if (MyDebug.LOG) Log.d(TAG, "restartOpenKamera")
        this.waitUntilImageQueueEmpty()
        // see http://stackoverflow.com/questions/2470870/force-application-to-restart-on-first-activity
        val intent =
            this.baseContext.packageManager.getLaunchIntentForPackage(this.baseContext.packageName)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        this.startActivity(intent)
    }

    fun takePhotoButtonLongClickCancelled() {
        if (MyDebug.LOG) Log.d(TAG, "takePhotoButtonLongClickCancelled")
        if (preview.cameraController != null && preview.cameraController!!.isContinuousBurstInProgress) {
            preview.cameraController!!.stopContinuousBurst()
        }
    }

    val saveLocationHistorySAF: SaveLocationHistory?
        get() = this.saveLocationHistorySaf

    fun usedFolderPicker() {
        if (applicationInterface.storageUtils.isUsingSAF) {
            saveLocationHistorySaf?.updateFolderHistory(storageUtils.saveLocationSAF, true)
        } else {
            saveLocationHistory.updateFolderHistory(storageUtils.saveLocation, true)
        }
    }

    fun hasThumbnailAnimation(): Boolean {
        return applicationInterface.hasThumbnailAnimation()
    } /*public boolean testHasNotification() {
        return hasNotification
    }*/

    companion object {
        private const val TAG = "MainActivity"

        private var activityCount = 0

        @JvmField
        @Volatile
        var testPreviewWantNoLimits: Boolean =
            false // test flag, if set to true then instead use testPreviewWantNoLimitsValue needs to be static, as it needs to be set before activity is created to take effect

        @JvmField
        @Volatile
        var testPreviewWantNoLimitsValue: Boolean = false

        @JvmField
        @Volatile
        var testForceSystemOrientation: Boolean =
            false // test flag, if set to true, that getSystemOrientation() returns testSystemOrientation

        @JvmField
        @Volatile
        var testSystemOrientation: SystemOrientation =
            SystemOrientation.PORTRAIT

        @JvmField
        @Volatile
        var testForceWindowInsets: Boolean =
            false // test flag, if set to true, then the OnApplyWindowInsetsListener will read from the following flags

        @JvmField
        @Volatile
        var testInsets: Insets? =
            null // test insets for WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout()

        @JvmField
        @Volatile
        var testCutoutInsets: Insets? = null // test insets for WindowInsets.Type.displayCutout()

        // application shortcuts:
        private const val ACTION_SHORTCUT_CAMERA = "com.hightechif.openkamera.SHORTCUT_CAMERA"
        private const val ACTION_SHORTCUT_SELFIE = "com.hightechif.openkamera.SHORTCUT_SELFIE"
        private const val ACTION_SHORTCUT_VIDEO = "com.hightechif.openkamera.SHORTCUT_VIDEO"
        private const val ACTION_SHORTCUT_GALLERY = "com.hightechif.openkamera.SHORTCUT_GALLERY"
        private const val ACTION_SHORTCUT_SETTINGS = "com.hightechif.openkamera.SHORTCUT_SETTINGS"

        private const val CHOOSE_SAVE_FOLDER_SAF_CODE = 42
        private const val CHOOSE_GHOST_IMAGE_SAF_CODE = 43
        private const val CHOOSE_LOAD_SETTINGS_SAF_CODE = 44

        @JvmField
        var testForceSupportsCamera2: Boolean =
            false // okay to be static, as this is set for an entire test suite

        // update: notifications now removed due to needing permissions on Android 13+
        //private boolean hasNotification
        //private final String CHANNEL_ID = "open_camera_channel"
        //private final int imageSavingNotificationId = 1
        private const val WATER_DENSITY_FRESHWATER = 1.0f
        private const val WATER_DENSITY_SALTWATER = 1.03f

        // whether to lock to landscape orientation, or allow switching between portrait and landscape orientations
        //public static final boolean LOCK_TO_LANDSCAPE = true
        const val LOCK_TO_LANDSCAPE: Boolean = false

        /** Whether to use codepaths that are compatible with scoped storage.
         */
        @JvmStatic
        fun useScopedStorage(): Boolean {
            //return false
            //return true
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        }

        /** Checks to see if the supplied folder (in the format as used by our preferences) is supported
         * with scoped storage.
         * @return The Boolean is always non-null, and returns whether the save location is valid.
         * If the return is false, then if the String is non-null, this stores an alternative
         * form that is valid. If null, there is no valid alternative.
         * @param baseFolder This should normally be null, but can be used to specify manually the
         * folder instead of using StorageUtils.getBaseFolder() - needed for unit
         * tests as Environment class (for Environment.getExternalStoragePublicDirectory())
         * is not mocked.
         */
        @JvmStatic
        @JvmOverloads
        fun checkSaveLocation(
            folder: String,
            baseFolder: String? = null
        ): CheckSaveLocationResult {
            /*if( MyDebug.LOG )
            Log.d(TAG, "DCIM path: " + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath())*/
            var baseFolder = baseFolder
            if (StorageUtils.saveFolderIsFull(folder)) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "checkSaveLocation for full path: $folder"
                )

                // But still check to see if the full path is part of DCIM. Since when using the
                // file dialog method with non-scoped storage, if the user specifies multiple subfolders
                // e.g. DCIM/blahA/blahB, we don't spot that in FolderChooserDialog.useFolder(), and
                // instead still store that as the full path.
                if (baseFolder == null) baseFolder = StorageUtils.baseFolder.absolutePath
                // strip '/' as last character - makes it easier to also spot cases where the folder is the
                // DCIM folder, but doesn't have a '/' last character
                if (baseFolder!!.isNotEmpty() && baseFolder[baseFolder.length - 1] == '/') baseFolder =
                    baseFolder.substring(0, baseFolder.length - 1)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    compare to base_folder: $baseFolder"
                )
                var altFolder: String? = null
                if (folder.startsWith(baseFolder)) {
                    altFolder = folder.substring(baseFolder.length)
                    // also need to strip the first '/' if it exists
                    if (altFolder.isNotEmpty() && altFolder[0] == '/') altFolder =
                        altFolder.substring(1)
                }

                return CheckSaveLocationResult(
                    false,
                    altFolder
                )
            } else {
                // already in expected format (indicates a sub-folder of DCIM)
                return CheckSaveLocationResult(true, null)
            }
        }

        @JvmStatic
        private fun getOnlineHelpUrl(append: String): String {
            if (MyDebug.LOG) Log.d(
                TAG,
                "getOnlineHelpUrl: $append"
            )
            // if we change this, remember that any page linked to must abide by Google Play developer policies!
            // also if we change this method name or where it's located, remember to update the mention in
            // OpenKamera_source.txt
            //return "https://OpenKamera.sourceforge.io/" + append
            return "https://OpenKamera.org.uk/$append"
        }

        /** Returns rotation in degrees (as a multiple of 90 degrees) corresponding to the supplied
         * system orientation.
         */
        @JvmStatic
        fun getRotationFromSystemOrientation(systemOrientation: SystemOrientation?): Int {
            val rotation =
                when (systemOrientation) {
                    SystemOrientation.PORTRAIT -> 270
                    SystemOrientation.REVERSE_LANDSCAPE -> 180
                    else -> 0
                }
            return rotation
        }

        /** Processes a user specified save folder. This should be used with the non-SAF scoped storage
         * method, where the user types a folder directly.
         */
        @JvmStatic
        fun processUserSaveLocation(folder: String): String {
            // filter repeated '/', e.g., replace // with /:
            var folder = folder
            val strip = "//"
            while (folder.isNotEmpty() && folder.contains(strip)) {
                folder = folder.replace(strip.toRegex(), "/")
            }

            if (folder.isNotEmpty() && folder[0] == '/') {
                // strip '/' as first character - as absolute paths not allowed with scoped storage
                // whilst we do block entering a '/' as first character in the InputFilter, users could
                // get around this (e.g., put a '/' as second character, then delete the first character)
                folder = folder.substring(1)
            }

            if (folder.isNotEmpty() && folder[folder.length - 1] == '/') {
                // strip '/' as last character - MediaStore will ignore it, but seems cleaner to strip it out anyway
                // (we still need to allow '/' as last character in the InputFilter, otherwise users won't be able to type it whilst writing a subfolder)
                folder = folder.substring(0, folder.length - 1)
            }

            return folder
        }

        @JvmStatic
        private fun putBundleExtra(bundle: Bundle, key: String, values: List<String>?) {
            if (values != null) {
                val valuesArr = arrayOfNulls<String>(values.size)
                for ((i, value) in values.withIndex()) {
                    valuesArr[i] = value
                }
                bundle.putStringArray(key, valuesArr)
            }
        }

        @JvmStatic
        fun performHapticFeedback(seekBar: SeekBar, lastHapticTime: Long): Long {
            var lastHapticTime = lastHapticTime
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(seekBar.context)
            if (sharedPreferences.getBoolean(
                    PreferenceKeys.ALLOW_HAPTIC_FEEDBACK_PREFERENCE_KEY,
                    true
                )
            ) {
                val timeMs = System.currentTimeMillis()
                if (timeMs > lastHapticTime + 16) {
                    lastHapticTime = timeMs
                    // SEGMENT_TICK or SEGMENT_TICK doesn't work on Galaxy S24+ at least, even though on Android 14!
                    /*if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ) {
                    seekBar.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                }
                else*/
                    run {
                        seekBar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
            return lastHapticTime
        }
    }
}