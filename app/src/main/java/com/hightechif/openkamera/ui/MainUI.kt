/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.preference.PreferenceManager
import android.util.Log
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MainActivity.SystemOrientation
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.R
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface.RawPref
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.utils.MyDebug
import com.hightechif.openkamera.utils.OnScreenIcons
import java.util.Hashtable
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** This contains functionality related to the main UI.
 */
class MainUI(val mainActivity: MainActivity) {

    private val onScreenIcons = OnScreenIcons(mainActivity)
    fun getOnScreenIcons(): OnScreenIcons = onScreenIcons

    @Volatile
    private var popupViewIsOpen = false // must be volatile for test project reading the state
    private var popupView: PopupView? = null
    private var forceDestroyPopup =
        false // if true, then the popup isn't cached for only the next time the popup is closed

    private var currentOrientation = 0

    enum class UIPlacement {
        UIPLACEMENT_RIGHT,
        UIPLACEMENT_LEFT,
        UIPLACEMENT_TOP
    }

    var uIPlacement: UIPlacement = UIPlacement.UIPLACEMENT_RIGHT
        private set
    var topIcon: View? = null
        private set
    private var navigationGapLandscapeAlignParentBottom = 0
    private var navigationGapReverseLandscapeAlignParentBottom = 0
    private var viewRotateAnimation = false
    private var viewRotateAnimationStart = 0f // for MainActivity.lockToLandscape==false
    private var immersiveMode = false
    private var showGuiPhoto =
        true // result of call to showGUI() - false means a "reduced" GUI is displayed, whilst taking photo or video
    private var showGuiVideo = true

    private var keydownVolumeUp = false
    private var keydownVolumeDown = false

    // For remote control: keep track of the currently highlighted
    // line and icon within the line
    private var remoteControlMode = false // whether remote control mode is enabled
    private var mPopupLine = 0
    private var mPopupIcon = 0
    private var mHighlightedLine: LinearLayout? = null
    private var mHighlightedIcon: View? = null
    private var mSelectingIcons = false
    private var mSelectingLines = false
    private var mExposureLine = 0
    private var mSelectingExposureUIElement = false
    private val highlightColor = Color.rgb(183, 28, 28) // Red 900
    private val highlightColorExposureUIElement = Color.rgb(244, 67, 54) // Red 500

    // for testing:
    private val testUiButtons: MutableMap<String, View> = Hashtable()
    var testSavedPopupWidth: Int = 0
    var testSavedPopupHeight: Int = 0

    @Volatile
    var testNavigationGap: Int = 0

    @Volatile
    var testNavigationGapLandscape: Int = 0

    @Volatile
    var testNavigationGapReversedLandscape: Int = 0

    private fun setSeekbarColors() {
        if (MyDebug.LOG) Log.d(TAG, "setSeekbarColors")
        run {
            val progressColor = ColorStateList.valueOf(Color.argb(255, 240, 240, 240))
            val thumbColor = ColorStateList.valueOf(Color.argb(255, 255, 255, 255))

            var seekBar: SeekBar = mainActivity.findViewById(R.id.zoom_seekbar)
            seekBar.progressTintList = progressColor
            seekBar.thumbTintList = thumbColor

            seekBar = mainActivity.findViewById(R.id.focus_seekbar)
            seekBar.progressTintList = progressColor
            seekBar.thumbTintList = thumbColor

            seekBar = mainActivity.findViewById(R.id.focus_bracketing_target_seekbar)
            seekBar.progressTintList = progressColor
            seekBar.thumbTintList = thumbColor

            seekBar = mainActivity.findViewById(R.id.exposure_seekbar)
            seekBar.progressTintList = progressColor
            seekBar.thumbTintList = thumbColor

            seekBar = mainActivity.findViewById(R.id.iso_seekbar)
            seekBar.progressTintList = progressColor
            seekBar.thumbTintList = thumbColor

            seekBar = mainActivity.findViewById(R.id.exposure_time_seekbar)
            seekBar.progressTintList = progressColor
            seekBar.thumbTintList = thumbColor

            seekBar = mainActivity.findViewById(R.id.white_balance_seekbar)
            seekBar.progressTintList = progressColor
            seekBar.thumbTintList = thumbColor
        }
    }

    /** Similar view.setRotation(uiRotation), but achieves this via an animation.
     */
    private fun setViewRotation(view: View, uiRotation: Float) {
        if (!viewRotateAnimation) {
            view.rotation = uiRotation
        }
        if (!MainActivity.lockToLandscape) {
            var startRotation = viewRotateAnimationStart + uiRotation
            if (startRotation >= 360.0f) startRotation -= 360.0f
            view.rotation = startRotation
        }
        var rotateBy = uiRotation - view.rotation
        if (rotateBy > 181.0f) rotateBy -= 360.0f
        else if (rotateBy < -181.0f) rotateBy += 360.0f
        // view.animate() modifies the view's rotation attribute, so it ends up equivalent to view.setRotation()
        // we use rotationBy() instead of rotation(), so we get the minimal rotation for clockwise vs anti-clockwise
        /*if( main_activity.isTest && Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2 ) {
            // We randomly get a java.lang.ArrayIndexOutOfBoundsException crash when running MainTests suite
            // on Android emulator with Android 4.3, from deep below ViewPropertyAnimator.start().
            // Unclear why this is - I haven't seen this on real devices and can't find out info about it.
            view.setRotation(uiRotation);
        }
        else*/
        run {
            view.animate().rotationBy(rotateBy)
                .setDuration(viewRotateAnimationDuration.toLong())
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }

    fun layoutUI() {
        layoutUI(false)
    }

    fun layoutUIWithRotation(viewRotateAnimationStart: Float) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "layoutUIWithRotation: $viewRotateAnimationStart"
        )
        this.viewRotateAnimation = true
        this.viewRotateAnimationStart = viewRotateAnimationStart
        layoutUI()
        viewRotateAnimation = false
        this.viewRotateAnimationStart = 0.0f
    }

    private fun computeUIPlacement(): UIPlacement {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val uiPlacementString =
            sharedPreferences.getString(PreferenceKeys.UI_PLACEMENT_PREFERENCE_KEY, "ui_top")!!
        return when (uiPlacementString) {
            "ui_left" -> UIPlacement.UIPLACEMENT_LEFT
            "ui_top" -> UIPlacement.UIPLACEMENT_TOP
            else -> UIPlacement.UIPLACEMENT_RIGHT
        }
    }

    // stores with width and height of the last time we laid out the UI
    var layoutUI_display_w: Int = -1
    var layoutUI_display_h: Int = -1

    private fun layoutUI(popupContainerOnly: Boolean) {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "layoutUI")
            debugTime = System.currentTimeMillis()
        }

        val systemOrientation: SystemOrientation = mainActivity.systemOrientation
        val systemOrientationPortrait =
            systemOrientation === SystemOrientation.PORTRAIT
        val systemOrientationReversedLandscape =
            systemOrientation === SystemOrientation.REVERSE_LANDSCAPE
        if (MyDebug.LOG) {
            Log.d(TAG, "    system_orientation = $systemOrientation")
            Log.d(
                TAG,
                "    system_orientation_portrait? $systemOrientationPortrait"
            )
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        // we cache the preferenceUiPlacement to save having to check it in the draw() method
        this.uIPlacement = computeUIPlacement()
        if (MyDebug.LOG) Log.d(TAG, "ui_placement: " + uIPlacement)
        val relativeOrientation: Int
        if (MainActivity.lockToLandscape) {
            // new code for orientation fixed to landscape
            // the display orientation should be locked to landscape, but how many degrees is that?
            val rotation: Int = mainActivity.getWindowManager().getDefaultDisplay().getRotation()
            var degrees = 0
            when (rotation) {
                Surface.ROTATION_0 -> degrees = 0
                Surface.ROTATION_90 -> degrees = 90
                Surface.ROTATION_180 -> degrees = 180
                Surface.ROTATION_270 -> degrees = 270
                else -> {}
            }
            // getRotation is anti-clockwise, but currentOrientation is clockwise, so we add rather than subtract
            // relativeOrientation is clockwise from landscape-left
            //int relativeOrientation = (currentOrientation + 360 - degrees) % 360;
            relativeOrientation = (currentOrientation + degrees) % 360
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "    current_orientation = $currentOrientation"
                )
                Log.d(TAG, "    degrees = $degrees")
                Log.d(
                    TAG,
                    "    relative_orientation = $relativeOrientation"
                )
            }
        } else {
            relativeOrientation = 0
        }
        val uiRotation = (360 - relativeOrientation) % 360
        mainActivity.preview.uIRotation = uiRotation
        // naming convention for variables is for systemOrientation==LANDSCAPE, right-handed UI
        var alignLeft =
            if (systemOrientationPortrait) RelativeLayout.ALIGN_TOP else RelativeLayout.ALIGN_LEFT
        var alignRight =
            if (systemOrientationPortrait) RelativeLayout.ALIGN_BOTTOM else RelativeLayout.ALIGN_RIGHT
        var alignTop =
            if (systemOrientationPortrait) RelativeLayout.ALIGN_RIGHT else RelativeLayout.ALIGN_TOP
        var alignBottom =
            if (systemOrientationPortrait) RelativeLayout.ALIGN_LEFT else RelativeLayout.ALIGN_BOTTOM
        var leftOf =
            if (systemOrientationPortrait) RelativeLayout.ABOVE else RelativeLayout.LEFT_OF
        var rightOf =
            if (systemOrientationPortrait) RelativeLayout.BELOW else RelativeLayout.RIGHT_OF
        var above =
            if (systemOrientationPortrait) RelativeLayout.RIGHT_OF else RelativeLayout.ABOVE
        var below =
            if (systemOrientationPortrait) RelativeLayout.LEFT_OF else RelativeLayout.BELOW
        var uiIndependentLeftOf = leftOf
        var uiIndependentRightOf = rightOf
        var uiIndependentAbove = above
        var uiIndependentBelow = below
        var alignParentLeft =
            if (systemOrientationPortrait) RelativeLayout.ALIGN_PARENT_TOP else RelativeLayout.ALIGN_PARENT_LEFT
        var alignParentRight =
            if (systemOrientationPortrait) RelativeLayout.ALIGN_PARENT_BOTTOM else RelativeLayout.ALIGN_PARENT_RIGHT
        var alignParentTop =
            if (systemOrientationPortrait) RelativeLayout.ALIGN_PARENT_RIGHT else RelativeLayout.ALIGN_PARENT_TOP
        var alignParentBottom =
            if (systemOrientationPortrait) RelativeLayout.ALIGN_PARENT_LEFT else RelativeLayout.ALIGN_PARENT_BOTTOM
        val centerHorizontal =
            if (systemOrientationPortrait) RelativeLayout.CENTER_VERTICAL else RelativeLayout.CENTER_HORIZONTAL
        val centerVertical =
            if (systemOrientationPortrait) RelativeLayout.CENTER_HORIZONTAL else RelativeLayout.CENTER_VERTICAL

        var iconpanelLeftOf = leftOf
        var iconpanelRightOf = rightOf
        var iconpanelAbove = above
        var iconpanelBelow = below
        var iconpanelAlignParentLeft = alignParentLeft
        var iconpanelAlignParentRight = alignParentRight
        var iconpanelAlignParentTop = alignParentTop
        var iconpanelAlignParentBottom = alignParentBottom

        if (systemOrientationReversedLandscape) {
            var temp = alignLeft
            alignLeft = alignRight
            alignRight = temp
            temp = alignTop
            alignTop = alignBottom
            alignBottom = temp
            temp = leftOf
            leftOf = rightOf
            rightOf = temp
            temp = above
            above = below
            below = temp

            uiIndependentLeftOf = leftOf
            uiIndependentRightOf = rightOf
            uiIndependentAbove = above
            uiIndependentBelow = below

            temp = alignParentLeft
            alignParentLeft = alignParentRight
            alignParentRight = temp
            temp = alignParentTop
            alignParentTop = alignParentBottom
            alignParentBottom = temp

            iconpanelLeftOf = leftOf
            iconpanelRightOf = rightOf
            iconpanelAbove = above
            iconpanelBelow = below
            iconpanelAlignParentLeft = alignParentLeft
            iconpanelAlignParentRight = alignParentRight
            iconpanelAlignParentTop = alignParentTop
            iconpanelAlignParentBottom = alignParentBottom
        }

        if (uIPlacement == UIPlacement.UIPLACEMENT_LEFT) {
            var temp = above
            above = below
            below = temp
            temp = alignParentTop
            alignParentTop = alignParentBottom
            alignParentBottom = temp
            iconpanelAlignParentTop = alignParentTop
            iconpanelAlignParentBottom = alignParentBottom
        } else if (uIPlacement == UIPlacement.UIPLACEMENT_TOP) {
            iconpanelLeftOf = below
            iconpanelRightOf = above
            iconpanelAbove = leftOf
            iconpanelBelow = rightOf
            iconpanelAlignParentLeft = alignParentBottom
            iconpanelAlignParentRight = alignParentTop
            iconpanelAlignParentTop = alignParentLeft
            iconpanelAlignParentBottom = alignParentRight
        }

        val displaySize = Point()
        mainActivity.applicationInterface.getDisplaySize(displaySize, true)
        this.layoutUI_display_w = displaySize.x
        this.layoutUI_display_h = displaySize.y
        if (MyDebug.LOG) {
            Log.d(TAG, "layoutUI_display_w: $layoutUI_display_w")
            Log.d(TAG, "layoutUI_display_h: $layoutUI_display_h")
        }
        val displayHeight = min(displaySize.x.toDouble(), displaySize.y.toDouble()).toInt()

        val scale: Float = mainActivity.getResources().getDisplayMetrics().density
        if (MyDebug.LOG) Log.d(TAG, "scale: $scale")

        /*int navigationGap = 0;
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ) {
            final int displayWidth = Math.max(display_size.x, display_size.y);
            Point realDisplaySize = new Point();
            display.getRealSize(realDisplaySize);
            final int realDisplayWidth = Math.max(real_display_size.x, real_display_size.y);
            navigationGap = realDisplayWidth - displayWidth;
            if( MyDebug.LOG ) {
                Log.d(TAG, "displayWidth: " + displayWidth);
                Log.d(TAG, "realDisplayWidth: " + realDisplayWidth);
                Log.d(TAG, "navigationGap: " + navigationGap);
            }
        }*/
        val navigationGap: Int = mainActivity.navigationGap
        val navigationGapLandscape: Int = mainActivity.navigationGapLandscape
        val navigationGapReverseLandscape: Int = mainActivity.navigationGapReverseLandscape
        // navigation gaps for UI elements that are aligned to alignParentBottom (the landscape edge, or reversed landscape edge if left-handed):
        this.navigationGapLandscapeAlignParentBottom = navigationGapLandscape
        this.navigationGapReverseLandscapeAlignParentBottom = navigationGapReverseLandscape
        if (uIPlacement == UIPlacement.UIPLACEMENT_LEFT) {
            navigationGapLandscapeAlignParentBottom = 0
        } else {
            navigationGapReverseLandscapeAlignParentBottom = 0
        }
        var galleryNavigationGap = navigationGap

        var galleryTopGap = 0
        run {
            // Leave space for the Android 12+ camera privacy indicator, as gallery icon would
            // otherwise overlap when in landscape orientation.
            // In theory we should use WindowInsets.getPrivacyIndicatorBounds() for this, but it seems
            // to give a much larger value when required (leaving to a much larger gap), as well as
            // obviously changing depending on orientation - but whilst this is only an issue for
            // landscape orientation, it looks better to keep the position consistent for any
            // orientation (otherwise the icons jump about when changing orientation, which looks
            // especially bad for UIPLACEMENT_RIGHT.
            // Not needed for UIPLACEMENT_LEFT - although still adjust the right hand side margin
            // for consistency.
            // We do for all Android versions for consistency (avoids testing overhead due to
            // different behaviour on different Android versions).
            if (uIPlacement != UIPlacement.UIPLACEMENT_LEFT) {
                // if we did want to do this for UIPLACEMENT_LEFT for consistency, it'd be the
                // "bottom" margin we need to change.
                galleryTopGap =
                    (privacyIndicatorGapDp * scale + 0.5f).toInt() // convert dps to pixels
            }
            val privacyIndicatorGap =
                (privacyIndicatorGapDp * scale + 0.5f).toInt() // convert dps to pixels
            galleryNavigationGap += privacyIndicatorGap
        }
        testNavigationGap = navigationGap
        testNavigationGapLandscape = navigationGapLandscape
        testNavigationGapReversedLandscape = navigationGapReverseLandscape
        if (MyDebug.LOG) {
            Log.d(TAG, "navigation_gap: $navigationGap")
            Log.d(
                TAG,
                "gallery_navigation_gap: $galleryNavigationGap"
            )
        }

        if (!popupContainerOnly) {
            // reset:
            topIcon = null

            // we use a dummy view, so that the GUI buttons keep their positioning even if the Settings button is hidden (visibility set to View.GONE)
            var view: View = mainActivity.findViewById(R.id.gui_anchor)
            var layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(iconpanelAlignParentLeft, 0)
            layoutParams.addRule(iconpanelAlignParentRight, RelativeLayout.TRUE)
            layoutParams.addRule(iconpanelAlignParentTop, RelativeLayout.TRUE)
            layoutParams.addRule(iconpanelAlignParentBottom, 0)
            layoutParams.addRule(iconpanelAbove, 0)
            layoutParams.addRule(iconpanelBelow, 0)
            layoutParams.addRule(iconpanelLeftOf, 0)
            layoutParams.addRule(iconpanelRightOf, 0)
            view.layoutParams = layoutParams
            setViewRotation(view, uiRotation.toFloat())
            var previousView = view

            val buttonsPermanent: MutableList<View> = ArrayList()
            if (uIPlacement == UIPlacement.UIPLACEMENT_TOP) {
                // not part of the icon panel in TOP mode
                view = mainActivity.findViewById(R.id.gallery)
                layoutParams = view.layoutParams as RelativeLayout.LayoutParams
                layoutParams.addRule(alignParentLeft, 0)
                layoutParams.addRule(alignParentRight, RelativeLayout.TRUE)
                layoutParams.addRule(alignParentTop, RelativeLayout.TRUE)
                layoutParams.addRule(alignParentBottom, 0)
                layoutParams.addRule(above, 0)
                layoutParams.addRule(below, 0)
                layoutParams.addRule(leftOf, 0)
                layoutParams.addRule(rightOf, 0)
                setMarginsForSystemUI(layoutParams, 0, galleryTopGap, galleryNavigationGap, 0)
                view.layoutParams = layoutParams
                setViewRotation(view, uiRotation.toFloat())
            } else {
                buttonsPermanent.add(mainActivity.findViewById(R.id.gallery))
            }
            buttonsPermanent.add(mainActivity.findViewById(R.id.settings))
            buttonsPermanent.add(mainActivity.findViewById(R.id.popup))
            buttonsPermanent.add(mainActivity.findViewById(R.id.exposure))
            //buttons_permanent.add(main_activity.findViewById(R.id.switch_video));
            //buttons_permanent.add(main_activity.findViewById(R.id.switch_camera));

            onScreenIcons.addOnScreenIcons(buttonsPermanent)

            buttonsPermanent.add(mainActivity.findViewById(R.id.kraken_icon))

            val buttonsAll: MutableList<View> = ArrayList(buttonsPermanent)
            // icons which only sometimes show on the icon panel:
            buttonsAll.add(mainActivity.findViewById(R.id.trash))
            buttonsAll.add(mainActivity.findViewById(R.id.share))

            for (thisView in buttonsAll) {
                layoutParams = thisView.layoutParams as RelativeLayout.LayoutParams
                layoutParams.addRule(iconpanelAlignParentLeft, 0)
                layoutParams.addRule(iconpanelAlignParentRight, 0)
                layoutParams.addRule(iconpanelAlignParentTop, RelativeLayout.TRUE)
                layoutParams.addRule(iconpanelAlignParentBottom, 0)
                layoutParams.addRule(iconpanelAbove, 0)
                layoutParams.addRule(iconpanelBelow, 0)
                layoutParams.addRule(iconpanelLeftOf, previousView.id)
                layoutParams.addRule(iconpanelRightOf, 0)
                thisView.layoutParams = layoutParams
                setViewRotation(thisView, uiRotation.toFloat())
                previousView = thisView
            }

            var buttonSize: Int =
                mainActivity.getResources().getDimensionPixelSize(R.dimen.onscreen_button_size)
            if (uIPlacement == UIPlacement.UIPLACEMENT_TOP) {
                // need to dynamically lay out the permanent icons

                var count = 0
                var firstVisibleView: View? = null
                var lastVisibleView: View? = null
                for (thisView in buttonsPermanent) {
                    if (thisView.visibility == View.VISIBLE) {
                        if (firstVisibleView == null) firstVisibleView = thisView
                        lastVisibleView = thisView
                        count++
                    }
                }
                //count = 10; // test
                if (MyDebug.LOG) {
                    Log.d(TAG, "count: $count")
                    Log.d(TAG, "display_height: $displayHeight")
                }
                if (count > 0) {
                    /*int buttonSize = displayHeight / count;
					if( MyDebug.LOG )
						Log.d(TAG, "buttonSize: " + buttonSize);
					for(View thisView : buttons) {
						if( this_view.getVisibility() == View.VISIBLE ) {
							layoutParams = (RelativeLayout.LayoutParams)this_view.getLayoutParams();
							layoutParams.width = buttonSize;
							layoutParams.height = buttonSize;
							this_view.setLayoutParams(layoutParams);
						}
					}*/
                    val totalButtonSize = count * buttonSize
                    var margin = 0
                    if (totalButtonSize > displayHeight) {
                        if (MyDebug.LOG) Log.d(TAG, "need to reduce button size")
                        buttonSize = displayHeight / count
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "need to increase margin")
                        if (count > 1) margin = (displayHeight - totalButtonSize) / (count - 1)
                    }
                    if (MyDebug.LOG) {
                        Log.d(TAG, "button_size: $buttonSize")
                        Log.d(
                            TAG,
                            "total_button_size: $totalButtonSize"
                        )
                        Log.d(TAG, "margin: $margin")
                    }
                    for (thisView in buttonsPermanent) {
                        if (thisView.visibility == View.VISIBLE) {
                            if (MyDebug.LOG) {
                                Log.d(TAG, "set view layout for: " + thisView.contentDescription)
                                if (thisView === firstVisibleView) {
                                    Log.d(TAG, "    first visible view")
                                }
                            }
                            //this_view.setPadding(0, margin/2, 0, margin/2);
                            layoutParams = thisView.layoutParams as RelativeLayout.LayoutParams
                            // be careful if we change how the margins are laid out: it looks nicer when only the settings icon
                            // is displayed (when taking a photo) if it is still shown left-most, rather than centred; also
                            // needed for "pause preview" trash/icons to be shown properly (test by rotating the phone to update
                            // the layout)
                            val marginFirst =
                                if (thisView === firstVisibleView) navigationGapReverseLandscape else margin / 2
                            val marginLast =
                                if (thisView === lastVisibleView) navigationGapLandscape else margin / 2
                            // avoid risk of privacy dot appearing on top of icon - in practice this is only a risk when in
                            // reverse landscape mode, but we apply in all orientations to avoid icons jumping about;
                            // similarly, as noted above we use a hardcoded dp rather than
                            // WindowInsets.getPrivacyIndicatorBounds(), as we want the icons to stay in the same location even as
                            // the device is rotated
                            val privacyGapLeft =
                                (12 * scale + 0.5f).toInt() // convert dps to pixels
                            setMarginsForSystemUI(
                                layoutParams,
                                privacyGapLeft,
                                marginFirst,
                                0,
                                marginLast
                            )
                            layoutParams.width = buttonSize
                            layoutParams.height = buttonSize
                            thisView.layoutParams = layoutParams
                        }
                    }
                    topIcon = firstVisibleView
                }
            } else {
                // need to reset size/margins to their default
                // except for gallery, which still needs its margins set for navigation gap! (and we
                // shouldn't change it's size, which isn't necessarily buttonSize)
                // other icons still needs margins set for navigationGapLandscape and navigationGapReverseLandscape
                view = mainActivity.findViewById(R.id.gallery)
                layoutParams = view.layoutParams as RelativeLayout.LayoutParams
                setMarginsForSystemUI(
                    layoutParams,
                    0,
                    max(
                        galleryTopGap.toDouble(),
                        navigationGapReverseLandscape.toDouble()
                    ).toInt(),
                    galleryNavigationGap,
                    navigationGapLandscape
                )
                view.layoutParams = layoutParams
                for (thisView in buttonsPermanent) {
                    if (thisView !== view) {
                        layoutParams = thisView.layoutParams as RelativeLayout.LayoutParams
                        setMarginsForSystemUI(
                            layoutParams,
                            0,
                            navigationGapReverseLandscape,
                            0,
                            navigationGapLandscape
                        )
                        layoutParams.width = buttonSize
                        layoutParams.height = buttonSize
                        thisView.layoutParams = layoutParams
                    }
                }
            }

            // end icon panel
            view = mainActivity.findViewById(R.id.take_photo)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(alignParentLeft, 0)
            layoutParams.addRule(alignParentRight, RelativeLayout.TRUE)
            layoutParams.addRule(alignParentTop, 0)
            layoutParams.addRule(alignParentBottom, 0)
            layoutParams.addRule(centerVertical, RelativeLayout.TRUE)
            layoutParams.addRule(centerHorizontal, 0)
            setMarginsForSystemUI(layoutParams, 0, 0, navigationGap, 0)
            view.layoutParams = layoutParams
            setViewRotation(view, uiRotation.toFloat())

            view = mainActivity.findViewById(R.id.switch_camera)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(alignParentLeft, 0)
            layoutParams.addRule(alignParentRight, RelativeLayout.TRUE)
            layoutParams.addRule(alignParentTop, 0)
            layoutParams.addRule(alignParentBottom, 0)
            layoutParams.addRule(uiIndependentAbove, R.id.take_photo)
            layoutParams.addRule(uiIndependentBelow, 0)
            layoutParams.addRule(uiIndependentLeftOf, 0)
            layoutParams.addRule(uiIndependentRightOf, 0)
            setMarginsForSystemUI(layoutParams, 0, 0, navigationGap, 0)
            view.layoutParams = layoutParams
            setViewRotation(view, uiRotation.toFloat())

            view = mainActivity.findViewById(R.id.switch_multi_camera)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(uiIndependentAbove, 0)
            layoutParams.addRule(uiIndependentBelow, 0)
            layoutParams.addRule(uiIndependentLeftOf, R.id.switch_camera)
            layoutParams.addRule(uiIndependentRightOf, 0)
            layoutParams.addRule(alignTop, R.id.switch_camera)
            layoutParams.addRule(alignBottom, R.id.switch_camera)
            layoutParams.addRule(alignLeft, 0)
            layoutParams.addRule(alignRight, 0)
            run {
                val margin = (5 * scale + 0.5f).toInt() // convert dps to pixels
                setMarginsForSystemUI(layoutParams, 0, 0, margin, 0)
            }
            view.layoutParams = layoutParams
            setViewRotation(view, uiRotation.toFloat())

            view = mainActivity.findViewById(R.id.pause_video)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(alignParentLeft, 0)
            layoutParams.addRule(alignParentRight, RelativeLayout.TRUE)
            layoutParams.addRule(alignParentTop, 0)
            layoutParams.addRule(alignParentBottom, 0)
            layoutParams.addRule(uiIndependentAbove, R.id.take_photo)
            layoutParams.addRule(uiIndependentBelow, 0)
            layoutParams.addRule(uiIndependentLeftOf, 0)
            layoutParams.addRule(uiIndependentRightOf, 0)
            setMarginsForSystemUI(layoutParams, 0, 0, navigationGap, 0)
            view.layoutParams = layoutParams
            setViewRotation(view, uiRotation.toFloat())

            view = mainActivity.findViewById(R.id.cancel_panorama)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(alignParentLeft, 0)
            layoutParams.addRule(alignParentRight, RelativeLayout.TRUE)
            layoutParams.addRule(alignParentTop, 0)
            layoutParams.addRule(alignParentBottom, 0)
            layoutParams.addRule(above, R.id.take_photo)
            layoutParams.addRule(below, 0)
            layoutParams.addRule(leftOf, 0)
            layoutParams.addRule(rightOf, 0)
            setMarginsForSystemUI(layoutParams, 0, 0, navigationGap, 0)
            view.layoutParams = layoutParams
            setViewRotation(view, uiRotation.toFloat())

            view = mainActivity.findViewById(R.id.switch_video)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(alignParentLeft, 0)
            layoutParams.addRule(alignParentRight, RelativeLayout.TRUE)
            layoutParams.addRule(alignParentTop, 0)
            layoutParams.addRule(alignParentBottom, 0)
            layoutParams.addRule(uiIndependentAbove, 0)
            layoutParams.addRule(uiIndependentBelow, R.id.take_photo)
            layoutParams.addRule(uiIndependentLeftOf, 0)
            layoutParams.addRule(uiIndependentRightOf, 0)
            setMarginsForSystemUI(layoutParams, 0, 0, navigationGap, 0)
            view.layoutParams = layoutParams
            setViewRotation(view, uiRotation.toFloat())

            view = mainActivity.findViewById(R.id.take_photo_when_video_recording)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(alignParentLeft, 0)
            layoutParams.addRule(alignParentRight, RelativeLayout.TRUE)
            layoutParams.addRule(alignParentTop, 0)
            layoutParams.addRule(alignParentBottom, 0)
            layoutParams.addRule(uiIndependentAbove, 0)
            layoutParams.addRule(uiIndependentBelow, R.id.take_photo)
            layoutParams.addRule(uiIndependentLeftOf, 0)
            layoutParams.addRule(uiIndependentRightOf, 0)
            setMarginsForSystemUI(layoutParams, 0, 0, navigationGap, 0)
            view.layoutParams = layoutParams
            setViewRotation(view, uiRotation.toFloat())

            view = mainActivity.findViewById(R.id.zoom_seekbar)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            // align close to the edge of screen
            layoutParams.addRule(alignParentLeft, 0)
            layoutParams.addRule(alignParentRight, RelativeLayout.TRUE)
            layoutParams.addRule(alignParentTop, 0)
            layoutParams.addRule(alignParentBottom, RelativeLayout.TRUE)
            // margins set below in setFixedRotation()
            // need to clear the others, in case we turn zoom controls on/off
            layoutParams.addRule(above, 0)
            layoutParams.addRule(below, 0)
            layoutParams.addRule(leftOf, 0)
            layoutParams.addRule(rightOf, 0)
            view.layoutParams = layoutParams
            val margin = (20 * scale + 0.5f).toInt() // convert dps to pixels
            setFixedRotation(
                mainActivity.findViewById(R.id.zoom_seekbar),
                0,
                navigationGapReverseLandscapeAlignParentBottom,
                margin + navigationGap,
                navigationGapLandscapeAlignParentBottom
            )

            view = mainActivity.findViewById(R.id.focus_seekbar)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(leftOf, R.id.zoom_seekbar)
            layoutParams.addRule(rightOf, 0)
            layoutParams.addRule(above, 0)
            layoutParams.addRule(below, 0)
            layoutParams.addRule(alignParentTop, 0)
            layoutParams.addRule(alignParentBottom, RelativeLayout.TRUE)
            layoutParams.addRule(alignParentLeft, 0)
            layoutParams.addRule(alignParentRight, 0)
            view.layoutParams = layoutParams

            view = mainActivity.findViewById(R.id.focus_bracketing_target_seekbar)
            layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            layoutParams.addRule(leftOf, R.id.zoom_seekbar)
            layoutParams.addRule(rightOf, 0)
            layoutParams.addRule(above, R.id.focus_seekbar)
            layoutParams.addRule(below, 0)
            view.layoutParams = layoutParams

            setFocusSeekbarsRotation()
        }

        if (!popupContainerOnly) {
            // set seekbar info
            var widthDp: Int
            if (!systemOrientationPortrait && (uiRotation == 0 || uiRotation == 180)) {
                // landscape
                widthDp = 350
            } else {
                // portrait
                widthDp = 250
                // prevent being too large on smaller devices (e.g., Galaxy Nexus or smaller)
                val maxWidthDp = getMaxHeightDp(true)
                if (widthDp > maxWidthDp) widthDp = maxWidthDp
            }
            if (MyDebug.LOG) Log.d(TAG, "width_dp: $widthDp")
            val heightDp = 50
            val widthPixels = (widthDp * scale + 0.5f).toInt() // convert dps to pixels
            val heightPixels = (heightDp * scale + 0.5f).toInt() // convert dps to pixels

            var view: View = mainActivity.findViewById(R.id.sliders_container)
            setViewRotation(view, uiRotation.toFloat())
            view.translationX = 0.0f
            view.translationY = 0.0f

            if (systemOrientationPortrait || uiRotation == 90 || uiRotation == 270) {
                // portrait
                if (systemOrientationPortrait) view.translationY = (2 * heightPixels).toFloat()
                else view.translationX = (2 * heightPixels).toFloat()
            } else if (uiRotation == 0) {
                // landscape
                view.translationY = heightPixels.toFloat()
            } else {
                // upside-down landscape
                view.translationY = (-1 * heightPixels).toFloat()
            }

            /*
            // align slidersContainer
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)view.getLayoutParams();
            if( systemOrientationPortrait || uiRotation == 90 || uiRotation == 270 ) {
                // portrait
                if( systemOrientationPortrait )
                    view.setTranslationY(2*heightPixels);
                else
                    view.setTranslationX(2*heightPixels);
                lp.addRule(leftOf, 0);
                lp.addRule(rightOf, 0);
                lp.addRule(above, 0);
                lp.addRule(below, 0);
                lp.addRule(alignParentTop, 0);
                lp.addRule(alignParentBottom, 0);
            }
            else if( uiRotation == (uiPlacement == UIPlacement.UIPLACEMENT_LEFT ? 180 : 0) ) {
                // landscape (or upside-down landscape if ui-left)
                view.setTranslationY(0);
                lp.addRule(leftOf, R.id.zoom_seekbar);
                lp.addRule(rightOf, 0);

                if( main_activity.showManualFocusSeekbar(true) ) {
                    lp.addRule(above, R.id.focus_bracketing_target_seekbar);
                    lp.addRule(below, 0);
                    lp.addRule(alignParentTop, 0);
                    lp.addRule(alignParentBottom, 0);
                }
                else if( main_activity.showManualFocusSeekbar(false) ) {
                    lp.addRule(above, R.id.focus_seekbar);
                    lp.addRule(below, 0);
                    lp.addRule(alignParentTop, 0);
                    lp.addRule(alignParentBottom, 0);
                }
                else {
                    lp.addRule(above, 0);
                    lp.addRule(below, 0);
                    lp.addRule(alignParentTop, 0);
                    lp.addRule(alignParentBottom, RelativeLayout.TRUE);
                }
            }
            else {
                // upside-down landscape (or landscape if ui-left)
                if( uiRotation == 0 )
                    view.setTranslationY(heightPixels);
                else
                    view.setTranslationY(-1*heightPixels);
                lp.addRule(leftOf, 0);
                lp.addRule(rightOf, 0);
                lp.addRule(above, 0);
                lp.addRule(below, 0);
                lp.addRule(alignParentBottom, 0);
            }
            view.setLayoutParams(lp);*/
            view = mainActivity.findViewById(R.id.exposure_seekbar)
            var lp = view.layoutParams as RelativeLayout.LayoutParams
            lp.width = widthPixels
            lp.height = heightPixels
            view.layoutParams = lp

            view = mainActivity.findViewById(R.id.iso_seekbar)
            lp = view.layoutParams as RelativeLayout.LayoutParams
            lp.width = widthPixels
            lp.height = heightPixels
            view.layoutParams = lp

            view = mainActivity.findViewById(R.id.exposure_time_seekbar)
            lp = view.layoutParams as RelativeLayout.LayoutParams
            lp.width = widthPixels
            lp.height = heightPixels
            view.layoutParams = lp

            view = mainActivity.findViewById(R.id.white_balance_seekbar)
            lp = view.layoutParams as RelativeLayout.LayoutParams
            lp.width = widthPixels
            lp.height = heightPixels
            view.layoutParams = lp
        }

        if (popupIsOpen()) {
            val view: View = mainActivity.findViewById(R.id.popup_container)
            val layoutParams = view.layoutParams as RelativeLayout.LayoutParams
            if (uIPlacement == UIPlacement.UIPLACEMENT_TOP) {
                layoutParams.addRule(alignRight, 0)
                layoutParams.addRule(alignBottom, 0)
                layoutParams.addRule(alignLeft, 0)
                layoutParams.addRule(alignTop, 0)
                layoutParams.addRule(above, 0)
                layoutParams.addRule(below, 0)
                layoutParams.addRule(leftOf, 0)
                layoutParams.addRule(rightOf, R.id.popup)
                layoutParams.addRule(
                    alignParentTop,
                    if (systemOrientationPortrait) 0 else RelativeLayout.TRUE
                )
                layoutParams.addRule(
                    alignParentBottom,
                    if (systemOrientationPortrait) 0 else RelativeLayout.TRUE
                )
                layoutParams.addRule(alignParentLeft, 0)
                layoutParams.addRule(alignParentRight, 0)
            } else {
                layoutParams.addRule(alignRight, R.id.popup)
                layoutParams.addRule(alignBottom, 0)
                layoutParams.addRule(alignLeft, 0)
                layoutParams.addRule(alignTop, 0)
                layoutParams.addRule(above, 0)
                layoutParams.addRule(below, R.id.popup)
                layoutParams.addRule(leftOf, 0)
                layoutParams.addRule(rightOf, 0)
                layoutParams.addRule(alignParentTop, 0)
                layoutParams.addRule(
                    alignParentBottom,
                    if (systemOrientationPortrait) 0 else RelativeLayout.TRUE
                )
                layoutParams.addRule(alignParentLeft, 0)
                layoutParams.addRule(alignParentRight, 0)
            }
            if (systemOrientationPortrait) {
                // limit height so doesn't take up full height of screen
                layoutParams.height = displayHeight
            }
            view.layoutParams = layoutParams

            //setPopupViewRotation(uiRotation, displayHeight);
            view.viewTreeObserver.addOnGlobalLayoutListener(
                object : OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (MyDebug.LOG) Log.d(TAG, "onGlobalLayout()")
                        // We need to call setPopupViewRotation after the above layout param changes
                        // have taken effect, otherwise we can have problems due to popupHeight being incorrect.
                        // Example bugs:
                        // Left-handed UI, portrait: Restart and open popup, it doesn't appear until device is rotated.
                        // Top UI, reverse-portrait: Restart and open popup, it appears in wrong location.
                        // Top UI, reverse-landscape: Restart and open popup, it appears in wrong location.
                        setPopupViewRotation(uiRotation, displayHeight)

                        // stop listening - only want to call this once!
                        view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            )
        }

        if (!popupContainerOnly) {
            setTakePhotoIcon()
            // no need to call setSwitchCameraContentDescription()
        }

        if (MyDebug.LOG) {
            Log.d(TAG, "layoutUI: total time: " + (System.currentTimeMillis() - debugTime))
        }
    }

    /** Wrapper for layoutParams.setMargins, but where the margins are supplied for landscape orientation,
     * and if in portrait these are automatically rotated.
     */
    fun setMarginsForSystemUI(
        layoutParams: RelativeLayout.LayoutParams,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val systemOrientation: SystemOrientation = mainActivity.systemOrientation
        if (systemOrientation === SystemOrientation.PORTRAIT) {
            layoutParams.setMargins(bottom, left, top, right)
        } else if (systemOrientation === SystemOrientation.REVERSE_LANDSCAPE) {
            layoutParams.setMargins(right, bottom, left, top)
        } else {
            layoutParams.setMargins(left, top, right, bottom)
        }
    }

    /** Some views (e.g. seekbars and zoom controls) are ones where we want to have a fixed
     * orientation as if in landscape mode, even if the system UI is portrait. So this method
     * sets a rotation so that the view appears as if in landscape orentation, and also sets
     * margins.
     * Note that Android has poor support for a rotated seekbar - we use view.setRotation(), but
     * this doesn't affect the bounds of the view! So as a hack, we modify the margins so the
     * view is positioned correctly. For this to work, the view must have a specified width
     * (which can be computed programmatically), rather than having both left and right sides being
     * aligned to another view.
     * The left/top/right/bottom margins should be supply for landscape orientation - these will
     * be automatically rotated if we're actually in portrait orientation.
     */
    private fun setFixedRotation(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        val systemOrientation: SystemOrientation = mainActivity.systemOrientation
        val rotation: Int =
            (360 - MainActivity.getRotationFromSystemOrientation(systemOrientation)) % 360
        view.rotation = rotation.toFloat()
        // set margins due to rotation
        val layoutParams = view.layoutParams as RelativeLayout.LayoutParams
        if (systemOrientation === SystemOrientation.PORTRAIT) {
            val diff = (layoutParams.width - layoutParams.height) / 2
            if (MyDebug.LOG) Log.d(TAG, "diff: $diff")
            setMarginsForSystemUI(
                layoutParams,
                diff + left,
                -diff + top,
                diff + right,
                -diff + bottom
            )
        } else {
            setMarginsForSystemUI(layoutParams, left, top, right, bottom)
        }
        view.layoutParams = layoutParams
    }

    fun setFocusSeekbarsRotation() {
        setFixedRotation(
            mainActivity.findViewById(R.id.focus_seekbar),
            0,
            navigationGapReverseLandscapeAlignParentBottom,
            0,
            navigationGapLandscapeAlignParentBottom
        )
        // don't need to set margins for navigation gap landscape for focusBracketingTargetSeekbar, as it sits above the source focusSeekbar
        setFixedRotation(
            mainActivity.findViewById(R.id.focus_bracketing_target_seekbar),
            0,
            0,
            0,
            0
        )
    }

    private fun setPopupViewRotation(uiRotation: Int, displayHeight: Int) {
        if (MyDebug.LOG) Log.d(TAG, "setPopupViewRotation")
        val view: View = mainActivity.findViewById(R.id.popup_container)
        setViewRotation(view, uiRotation.toFloat())
        // reset:
        view.translationX = 0.0f
        view.translationY = 0.0f

        val popupWidth = view.width
        val popupHeight = view.height
        testSavedPopupWidth = popupWidth
        testSavedPopupHeight = popupHeight
        if (MyDebug.LOG) {
            Log.d(TAG, "popup_width: $popupWidth")
            Log.d(TAG, "popup_height: $popupHeight")
            if (popupView != null) Log.d(TAG, "popup total width: " + popupView!!.totalWidth)
        }
        if (popupView != null && popupWidth > popupView!!.totalWidth * 1.2) {
            // This is a workaround for the rare but annoying bug where the popup window is too large
            // (and appears partially off-screen). Unfortunately have been unable to fix - and trying
            // to force the popup container to have a particular width just means some of the contents
            // (e.g., Timer) are missing. But at least stop caching it, so that reopening the popup
            // should fix it, rather than having to restart or pause/resume Open Kamera.
            // Also note, normally we should expect popupWidth == popup_view.totalWidth, but
            // have put a fudge factor of 1.2 just in case it's normally slightly larger on some
            // devices.
            Log.e(TAG, "### popup view is too big?!")
            forceDestroyPopup = true
            /*popupWidth = popup_view.totalWidth;
			ViewGroup.LayoutParams params = new RelativeLayout.LayoutParams(
					popupWidth,
					RelativeLayout.LayoutParams.WRAP_CONTENT);
			view.setLayoutParams(params);*/
        } else {
            forceDestroyPopup = false
        }

        if (uiRotation == 0 || uiRotation == 180) {
            view.pivotX = popupWidth / 2.0f
            view.pivotY = popupHeight / 2.0f
        } else if (uIPlacement == UIPlacement.UIPLACEMENT_TOP) {
            view.pivotX = 0.0f
            view.pivotY = 0.0f
            if (uiRotation == 90) {
                view.translationX = popupHeight.toFloat()
            } else if (uiRotation == 270) {
                view.translationY = displayHeight.toFloat()
            }
        } else {
            view.pivotX = popupWidth.toFloat()
            view.pivotY =
                if (uIPlacement == UIPlacement.UIPLACEMENT_RIGHT) 0.0f else popupHeight.toFloat()
            if (uIPlacement == UIPlacement.UIPLACEMENT_RIGHT) {
                if (uiRotation == 90) {
                    view.translationY = popupWidth.toFloat()
                } else if (uiRotation == 270) view.translationX = -popupHeight.toFloat()
            } else {
                if (uiRotation == 90) view.translationX = -popupHeight.toFloat()
                else if (uiRotation == 270) view.translationY = -popupWidth.toFloat()
            }
        }
    }

    /** Set icons for taking photos vs videos.
     * Also handles content descriptions for the take photo button and switch video button.
     */
    fun setTakePhotoIcon() {
        if (MyDebug.LOG) Log.d(TAG, "setTakePhotoIcon()")
        if (mainActivity.preview != null) {
            var view: ImageButton = mainActivity.findViewById(R.id.take_photo)
            var resource: Int
            val contentDescription: Int
            val switchVideoContentDescription: Int
            if (mainActivity.preview.isVideo) {
                if (MyDebug.LOG) Log.d(TAG, "set icon to video")
                resource = if (mainActivity.preview
                        .isVideoRecording
                ) R.drawable.take_video_recording else R.drawable.take_video_selector
                contentDescription = if (mainActivity.preview
                        .isVideoRecording
                ) R.string.stop_video else R.string.start_video
                switchVideoContentDescription = R.string.switch_to_photo
            } else if (mainActivity.applicationInterface.photoMode === MyApplicationInterface.PhotoMode.Panorama &&
                mainActivity.applicationInterface.gyroSensor.isRecording
            ) {
                if (MyDebug.LOG) Log.d(TAG, "set icon to recording panorama")
                resource = R.drawable.baseline_check_white_48
                contentDescription = R.string.finish_panorama
                switchVideoContentDescription = R.string.switch_to_video
            } else {
                if (MyDebug.LOG) Log.d(TAG, "set icon to photo")
                resource = R.drawable.take_photo_selector
                contentDescription = R.string.take_photo
                switchVideoContentDescription = R.string.switch_to_video
            }
            view.setImageResource(resource)
            view.contentDescription = mainActivity.getResources().getString(contentDescription)
            view.tag = resource // for testing

            view = mainActivity.findViewById(R.id.switch_video)
            view.contentDescription =
                mainActivity.getResources().getString(switchVideoContentDescription)
            resource = if (mainActivity.preview?.isVideo == true) R.drawable.take_photo
            else R.drawable.take_video
            view.setImageResource(resource)
            view.tag = resource // for testing
        }
    }

    /** Set content description for switch camera button.
     */
    fun setSwitchCameraContentDescription() {
        if (MyDebug.LOG) Log.d(TAG, "setSwitchCameraContentDescription()")
        if (mainActivity.preview != null && mainActivity.preview
                ?.canSwitchCamera() == true
        ) {
            val view: ImageButton = mainActivity.findViewById(R.id.switch_camera)
            val contentDescription: Int
            val cameraId: Int = mainActivity.nextCameraId
            contentDescription =
                when (mainActivity.preview?.cameraControllerManager?.getFacing(cameraId)) {
                    CameraController.Facing.FACING_FRONT -> R.string.switch_to_front_camera
                    CameraController.Facing.FACING_BACK -> R.string.switch_to_back_camera
                    CameraController.Facing.FACING_EXTERNAL -> R.string.switch_to_external_camera
                    else -> R.string.switch_to_unknown_camera
                }
            if (MyDebug.LOG) Log.d(
                TAG,
                "content_description: " + mainActivity.getResources()
                    .getString(contentDescription)
            )
            view.contentDescription = mainActivity.getResources().getString(contentDescription)
        }
    }

    /** Set content description for pause video button.
     */
    fun setPauseVideoContentDescription() {
        if (MyDebug.LOG) Log.d(TAG, "setPauseVideoContentDescription()")
        val pauseVideoButton: ImageButton = mainActivity.findViewById(R.id.pause_video)
        val contentDescription: Int
        if (mainActivity.preview?.isVideoRecordingPaused == true) {
            contentDescription = R.string.resume_video
            pauseVideoButton.setImageResource(R.drawable.ic_play_circle_outline_white_48dp)
        } else {
            contentDescription = R.string.pause_video
            pauseVideoButton.setImageResource(R.drawable.ic_pause_circle_outline_white_48dp)
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "content_description: " + mainActivity.getResources().getString(contentDescription)
        )
        pauseVideoButton.contentDescription =
            mainActivity.getResources().getString(contentDescription)
    }

    fun updateRemoteConnectionIcon() {
        val remoteConnectedIcon: View = mainActivity.findViewById(R.id.kraken_icon)
        if (mainActivity.bluetoothRemoteControl?.remoteConnected() == true) {
            if (MyDebug.LOG) Log.d(TAG, "Remote control connected")
            remoteConnectedIcon.visibility = View.VISIBLE
        } else {
            if (MyDebug.LOG) Log.d(TAG, "Remote control DISconnected")
            remoteConnectedIcon.visibility = View.GONE
        }
    }

    // ParameterCanBeLocal warning suppressed as it's incorrect here! (Or
    // possibly it's due to effect of MainActivity.lockToLandscape always
    // being false.)
    fun onOrientationChanged(orientation: Int) {
        /*if( MyDebug.LOG ) {
			Log.d(TAG, "onOrientationChanged()");
			Log.d(TAG, "orientation: " + orientation);
			Log.d(TAG, "currentOrientation: " + currentOrientation);
		}*/
        var orientation = orientation
        if (!MainActivity.lockToLandscape) return
        // if locked to landscape, we need to handle the orientation change ourselves
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
        var diff = abs((orientation - currentOrientation).toDouble()).toInt()
        if (diff > 180) diff = 360 - diff
        // only change orientation when sufficiently changed
        if (diff > 60) {
            orientation = (orientation + 45) / 90 * 90
            orientation = orientation % 360
            if (orientation != currentOrientation) {
                this.currentOrientation = orientation
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "current_orientation is now: $currentOrientation"
                    )
                }
                viewRotateAnimation = true
                layoutUI()
                viewRotateAnimation = false

                // Call DrawPreview.updateSettings() so that we reset calculations that depend on
                // getLocationOnScreen() - since the result is affected by a View's rotation, we need
                // to recompute - this also means we need to delay slightly until after the rotation
                // animation is complete.
                // To reproduce issues, rotate from upside-down-landscape to portrait, and observe
                // the info-text placement (when using icons-along-top), or with on-screen angle
                // displayed when in 16:9 preview.
                // Potentially we could use Animation.setAnimationListener(), but we set a separate
                // animation for every icon.
                // Note, this seems to be unneeded due to the fix in DrawPreview for
                // "getRotation() == 180.0f", but good to clear the cached values (e.g., in case we
                // compute them during when the icons are being rotated).
                val handler = Handler()
                handler.postDelayed({
                    if (MyDebug.LOG) Log.d(TAG, "onOrientationChanged->postDelayed()")
                    mainActivity.applicationInterface?.drawPreview?.updateSettings()
                }, (viewRotateAnimationDuration + 20).toLong())
            }
        }
    }

    fun showExposureLockIcon(): Boolean {
        if (mainActivity.preview?.supportsExposureLock() != true) return false
        if (mainActivity.applicationInterface?.isCameraExtensionPref() == true) {
            // not supported for camera extensions
            return false
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_EXPOSURE_LOCK_PREFERENCE_KEY, true)
    }

    fun showWhiteBalanceLockIcon(): Boolean {
        if (!mainActivity.preview.supportsWhiteBalanceLock()) return false
        if (mainActivity.applicationInterface.isCameraExtensionPref()) {
            // not supported for camera extensions
            return false
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_WHITE_BALANCE_LOCK_PREFERENCE_KEY, false)
    }

    fun showCycleRawIcon(): Boolean {
        if (!mainActivity.preview.supportsRaw()) return false
        if (!mainActivity.applicationInterface
                .isRawAllowed(mainActivity.applicationInterface.photoMode)
        ) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_CYCLE_RAW_PREFERENCE_KEY, false)
    }

    fun showStoreLocationIcon(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_STORE_LOCATION_PREFERENCE_KEY, false)
    }

    fun showTextStampIcon(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_TEXT_STAMP_PREFERENCE_KEY, false)
    }

    fun showStampIcon(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_STAMP_PREFERENCE_KEY, false)
    }

    fun showFocusPeakingIcon(): Boolean {
        if (!mainActivity.supportsPreviewBitmaps()) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_FOCUS_PEAKING_PREFERENCE_KEY, false)
    }

    fun showAutoLevelIcon(): Boolean {
        if (!mainActivity.supportsAutoStabilise()) return false
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_AUTO_LEVEL_PREFERENCE_KEY, false)
    }

    fun showCycleFlashIcon(): Boolean {
        if (!mainActivity.preview.supportsFlash()) return false
        if (mainActivity.preview.isVideo) return false // no point showing flash icon in video mode, as we only allow flash auto and flash torch, and we don't support torch on the on-screen cycle flash icon

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_CYCLE_FLASH_PREFERENCE_KEY, false)
    }

    fun showFaceDetectionIcon(): Boolean {
        if (!mainActivity.preview.supportsFaceDetection()) return false
        if (mainActivity.applicationInterface.isCameraExtensionPref()) {
            // not supported for camera extensions
            return false
        }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_FACE_DETECTION_PREFERENCE_KEY, false)
    }

    fun setImmersiveMode(immersiveMode: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setImmersiveMode: $immersiveMode"
        )
        this.immersiveMode = immersiveMode
        mainActivity.runOnUiThread(Runnable {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            // if going into immersive mode, the we should set GONE the ones that are set GONE in showGUI(false)
            //final int visibilityGone = immersiveMode ? View.GONE : View.VISIBLE;
            val visibility = if (immersiveMode) View.GONE else View.VISIBLE
            if (MyDebug.LOG) Log.d(
                TAG,
                "setImmersiveMode: set visibility: $visibility"
            )
            // n.b., don't hide share and trash buttons, as they require immediate user input for us to continue
            val switchCameraButton: View = mainActivity.findViewById(R.id.switch_camera)
            val switchMultiCameraButton: View = mainActivity.findViewById(R.id.switch_multi_camera)
            val switchVideoButton: View = mainActivity.findViewById(R.id.switch_video)
            val exposureButton: View = mainActivity.findViewById(R.id.exposure)
            val popupButton: View = mainActivity.findViewById(R.id.popup)
            val galleryButton: View = mainActivity.findViewById(R.id.gallery)
            val settingsButton: View = mainActivity.findViewById(R.id.settings)
            val zoomSeekBar: View = mainActivity.findViewById(R.id.zoom_seekbar)
            val focusSeekBar: View = mainActivity.findViewById(R.id.focus_seekbar)
            val focusBracketingTargetSeekBar: View =
                mainActivity.findViewById(R.id.focus_bracketing_target_seekbar)
            if ((mainActivity.preview.cameraControllerManager?.numberOfCameras ?: 0) > 1)
                switchCameraButton.visibility = visibility
            if (mainActivity.showSwitchMultiCamIcon()) switchMultiCameraButton.visibility =
                visibility
            switchVideoButton.visibility = visibility
            if (mainActivity.supportsExposureButton()) exposureButton.visibility = visibility
            onScreenIcons.setVisibility(visibility, visibility)
            popupButton.visibility = visibility
            galleryButton.visibility = visibility
            settingsButton.visibility = visibility
            if (MyDebug.LOG) {
                Log.d(TAG, "has_zoom: " + mainActivity.preview.supportsZoom())
            }
            if (mainActivity.preview.supportsZoom() && sharedPreferences.getBoolean(
                    PreferenceKeys.SHOW_ZOOM_SLIDER_CONTROLS_PREFERENCE_KEY,
                    true
                )
            ) {
                zoomSeekBar.visibility = visibility
            }
            if (mainActivity.showManualFocusSeekbar(false)) focusSeekBar.visibility =
                visibility
            if (mainActivity.showManualFocusSeekbar(true)) focusBracketingTargetSeekBar.visibility =
                visibility
            val prefImmersiveMode = sharedPreferences.getString(
                PreferenceKeys.IMMERSIVE_MODE_PREFERENCE_KEY,
                "immersive_mode_off"
            )
            if (prefImmersiveMode == "immersive_mode_everything") {
                if (sharedPreferences.getBoolean(PreferenceKeys.SHOW_TAKE_PHOTO_PREFERENCE_KEY, true)) {
                    val takePhotoButton: View = mainActivity.findViewById(R.id.take_photo)
                    takePhotoButton.visibility = visibility
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mainActivity.preview
                        .isVideoRecording
                ) {
                    val pauseVideoButton: View = mainActivity.findViewById(R.id.pause_video)
                    pauseVideoButton.visibility = visibility
                }
                if (mainActivity.preview
                        .supportsPhotoVideoRecording() && mainActivity.applicationInterface
                        .usePhotoVideoRecording() && mainActivity.preview.isVideoRecording
                ) {
                    val takePhotoVideoButton: View =
                        mainActivity.findViewById(R.id.take_photo_when_video_recording)
                    takePhotoVideoButton.visibility = visibility
                }
                if (mainActivity.applicationInterface.gyroSensor.isRecording) {
                    val cancelPanoramaButton: View =
                        mainActivity.findViewById(R.id.cancel_panorama)
                    cancelPanoramaButton.visibility = visibility
                }
            }
            if (!immersiveMode) {
                // make sure the GUI is set up as expected
                showGUI()
            }
        })
    }

    fun inImmersiveMode(): Boolean {
        return immersiveMode
    }

    fun showGUI(show: Boolean, isVideo: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "showGUI: $show")
            Log.d(TAG, "is_video: $isVideo")
        }
        if (isVideo) this.showGuiVideo = show
        else this.showGuiPhoto = show
        showGUI()
    }

    fun showGUI() {
        if (MyDebug.LOG) {
            Log.d(TAG, "showGUI")
            Log.d(TAG, "show_gui_photo: $showGuiPhoto")
            Log.d(TAG, "show_gui_video: $showGuiVideo")
        }
        if (inImmersiveMode()) return
        if ((showGuiPhoto || showGuiVideo) && mainActivity.usingKitKatImmersiveMode()) {
            // call to reset the timer
            mainActivity.initImmersiveMode()
        }
        mainActivity.runOnUiThread(Runnable {
            val isPanoramaRecording: Boolean =
                mainActivity.applicationInterface.gyroSensor.isRecording
            val visibility =
                if (isPanoramaRecording) View.GONE else if (showGuiPhoto && showGuiVideo) View.VISIBLE else View.GONE // for UI that is hidden while taking photo or video
            val visibilityVideo =
                if (isPanoramaRecording) View.GONE else if (showGuiPhoto) View.VISIBLE else View.GONE // for UI that is only hidden while taking photo
            val settingsButton: View = mainActivity.findViewById(R.id.settings)
            val switchCameraButton: View = mainActivity.findViewById(R.id.switch_camera)
            val switchMultiCameraButton: View = mainActivity.findViewById(R.id.switch_multi_camera)
            val switchVideoButton: View = mainActivity.findViewById(R.id.switch_video)
            val exposureButton: View = mainActivity.findViewById(R.id.exposure)
            val popupButton: View = mainActivity.findViewById(R.id.popup)
            settingsButton.visibility = visibilityVideo
            if ((mainActivity.preview.cameraControllerManager?.numberOfCameras ?: 0) > 1)
                switchCameraButton.visibility = visibility
            if (mainActivity.showSwitchMultiCamIcon()) switchMultiCameraButton.visibility =
                visibility
            switchVideoButton.visibility = visibility
            if (mainActivity.supportsExposureButton()) exposureButton.visibility =
                visibilityVideo // still allow exposure when recording video
            onScreenIcons.setVisibility(visibility, visibilityVideo)
            if (!(showGuiPhoto && showGuiVideo)) {
                closePopup() // we still allow the popup when recording video, but need to update the UI (so it only shows flash options), so easiest to just close
            }

            val remoteConnectedIcon: View = mainActivity.findViewById(R.id.kraken_icon)
            if (mainActivity.bluetoothRemoteControl.remoteConnected()) {
                if (MyDebug.LOG) Log.d(TAG, "Remote control connected")
                remoteConnectedIcon.visibility = View.VISIBLE
            } else {
                if (MyDebug.LOG) Log.d(TAG, "Remote control DISconnected")
                remoteConnectedIcon.visibility = View.GONE
            }
            popupButton.visibility = if (mainActivity.preview
                    .supportsFlash()
            ) visibilityVideo else visibility // still allow popup in order to change flash mode when recording video
            if (showGuiPhoto && showGuiVideo) {
                layoutUI() // needed for "top" UIPlacement, to auto-arrange the buttons
            }
        })
    }

    fun updateExposureLockIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.exposure_lock)
        val enabled: Boolean = mainActivity.preview.isExposureLocked
        view.setImageResource(if (enabled) R.drawable.exposure_locked else R.drawable.exposure_unlocked)
        view.contentDescription = mainActivity.getResources()
            .getString(if (enabled) R.string.exposure_unlock else R.string.exposure_lock)
    }

    fun updateWhiteBalanceLockIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.white_balance_lock)
        val enabled: Boolean = mainActivity.preview.isWhiteBalanceLocked
        view.setImageResource(if (enabled) R.drawable.white_balance_locked else R.drawable.white_balance_unlocked)
        view.contentDescription = mainActivity.getResources()
            .getString(if (enabled) R.string.white_balance_unlock else R.string.white_balance_lock)
    }

    fun updateCycleRawIcon() {
        val rawPref: RawPref = mainActivity.applicationInterface.getRawPref()
        val view: ImageButton = mainActivity.findViewById(R.id.cycle_raw)
        if (rawPref === RawPref.RAWPREF_JPEG_DNG) {
            if (mainActivity.applicationInterface.isRawOnly) {
                // actually RAW only
                view.setImageResource(R.drawable.raw_only_icon)
            } else {
                view.setImageResource(R.drawable.raw_icon)
            }
        } else {
            view.setImageResource(R.drawable.raw_off_icon)
        }
    }

    fun updateStoreLocationIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.store_location)
        val enabled: Boolean = mainActivity.applicationInterface.getGeotaggingPref()
        view.setImageResource(if (enabled) R.drawable.ic_gps_fixed_red_48dp else R.drawable.ic_gps_fixed_white_48dp)
        view.contentDescription = mainActivity.getResources()
            .getString(if (enabled) R.string.preference_location_disable else R.string.preference_location_enable)
    }

    fun updateTextStampIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.text_stamp)
        val enabled: Boolean = mainActivity.applicationInterface.textStampPref.isNotEmpty()
        view.setImageResource(if (enabled) R.drawable.baseline_text_fields_red_48 else R.drawable.baseline_text_fields_white_48)
    }

    fun updateStampIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.stamp)
        val enabled: Boolean =
            mainActivity.applicationInterface.stampPref.equals("preference_stamp_yes")
        view.setImageResource(if (enabled) R.drawable.ic_text_format_red_48dp else R.drawable.ic_text_format_white_48dp)
        view.contentDescription = mainActivity.getResources()
            .getString(if (enabled) R.string.stamp_disable else R.string.stamp_enable)
    }

    fun updateFocusPeakingIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.focus_peaking)
        val enabled: Boolean = mainActivity.applicationInterface.focusPeakingPref
        view.setImageResource(if (enabled) R.drawable.key_visualizer_red else R.drawable.key_visualizer)
        view.contentDescription = mainActivity.getResources()
            .getString(if (enabled) R.string.focus_peaking_disable else R.string.focus_peaking_enable)
    }

    fun updateAutoLevelIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.auto_level)
        val enabled: Boolean = mainActivity.applicationInterface.autoStabilisePref
        view.setImageResource(if (enabled) R.drawable.auto_stabilise_icon_red else R.drawable.auto_stabilise_icon)
        view.contentDescription = mainActivity.getResources()
            .getString(if (enabled) R.string.auto_level_disable else R.string.auto_level_enable)
    }

    fun updateCycleFlashIcon() {
        // n.b., read from preview rather than saved application preference - so the icon updates correctly when in flash
        // auto mode, but user switches to manual ISO where flash auto isn't supported
        val flashValue: String? = mainActivity.preview.currentFlashValue
        if (flashValue != null) {
            val view: ImageButton = mainActivity.findViewById(R.id.cycle_flash)
            when (flashValue) {
                "flash_off" -> view.setImageResource(R.drawable.flash_off)
                "flash_auto", "flash_frontscreen_auto" -> view.setImageResource(R.drawable.flash_auto)
                "flash_on", "flash_frontscreen_on" -> view.setImageResource(R.drawable.flash_on)
                "flash_torch", "flash_frontscreen_torch" -> view.setImageResource(R.drawable.baseline_highlight_white_48)
                "flash_red_eye" -> view.setImageResource(R.drawable.baseline_remove_red_eye_white_48)
                else -> {
                    // just in case??
                    Log.e(TAG, "unknown flash value $flashValue")
                    view.setImageResource(R.drawable.flash_off)
                }
            }
        } else {
            val view: ImageButton = mainActivity.findViewById(R.id.cycle_flash)
            view.setImageResource(R.drawable.flash_off)
        }
    }

    fun updateFaceDetectionIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.face_detection)
        val enabled: Boolean = mainActivity.applicationInterface.getFaceDetectionPref()
        view.setImageResource(if (enabled) R.drawable.ic_face_red_48dp else R.drawable.ic_face_white_48dp)
        view.contentDescription = mainActivity.resources
            .getString(if (enabled) R.string.face_detection_disable else R.string.face_detection_enable)
    }

    fun updateCycleLockOrientationIcon() {
        val view: ImageButton = mainActivity.findViewById(R.id.cycle_lock_orientation)
        val lockOrientation: String = mainActivity.applicationInterface.getLockOrientationPref()
        when (lockOrientation) {
            "portrait" -> {
                view.setImageResource(R.drawable.mobile_lock_portrait_48px_red)
                view.contentDescription =
                    mainActivity.resources.getString(R.string.cycle_lock_orientation)
            }

            "landscape" -> {
                view.setImageResource(R.drawable.mobile_lock_landscape_48px_red)
                view.contentDescription =
                    mainActivity.resources.getString(R.string.cycle_lock_orientation)
            }

            else -> {
                view.setImageResource(R.drawable.mobile_unlock_48px)
                view.contentDescription =
                    mainActivity.resources.getString(R.string.cycle_lock_orientation)
            }
        }
    }

    fun updateOnScreenIcons() {
        if (MyDebug.LOG) Log.d(TAG, "updateOnScreenIcons")
        onScreenIcons.updateOnScreenIcons()
    }

    fun audioControlStarted() {
        val view: ImageButton = mainActivity.findViewById(R.id.audio_control)
        view.setImageResource(R.drawable.ic_mic_red_48dp)
        view.contentDescription =
            mainActivity.getResources().getString(R.string.audio_control_stop)
    }

    fun audioControlStopped() {
        val view: ImageButton = mainActivity.findViewById(R.id.audio_control)
        view.setImageResource(R.drawable.ic_mic_white_48dp)
        view.contentDescription =
            mainActivity.getResources().getString(R.string.audio_control_start)
    }

    val isExposureUIOpen: Boolean
        get() {
            val exposureSeekBar: View = mainActivity.findViewById(R.id.exposure_container)
            val exposureVisibility = exposureSeekBar.visibility
            val manualExposureSeekBar: View =
                mainActivity.findViewById(R.id.manual_exposure_container)
            val manualExposureVisibility = manualExposureSeekBar.visibility
            return exposureVisibility == View.VISIBLE || manualExposureVisibility == View.VISIBLE
        }

    /**
     * Opens or close the exposure settings (ISO, white balance, etc)
     */
    fun toggleExposureUI() {
        if (MyDebug.LOG) Log.d(TAG, "toggleExposureUI")
        closePopup()
        mSelectingExposureUIElement = false
        if (isExposureUIOpen) {
            closeExposureUI()
        } else if (mainActivity.preview.cameraController != null && mainActivity.supportsExposureButton()
        ) {
            setupExposureUI()
            if (mainActivity.bluetoothRemoteControl.remoteEnabled()) {
                initRemoteControlForExposureUI()
            }
        }
    }

    private fun initRemoteControlForExposureUI() {
        if (MyDebug.LOG) Log.d(TAG, "initRemoteControlForExposureUI")
        if (isExposureUIOpen) { // just in case
            remoteControlMode = true
            mExposureLine = 0
            highlightExposureUILine(true)
        }
    }

    private fun clearRemoteControlForExposureUI() {
        if (MyDebug.LOG) Log.d(TAG, "clearRemoteControlForExposureUI")
        if (isExposureUIOpen && remoteControlMode) {
            remoteControlMode = false
            resetExposureUIHighlights()
        }
    }

    private fun resetExposureUIHighlights() {
        if (MyDebug.LOG) Log.d(TAG, "resetExposureUIHighlights")
        val isoButtonsContainer: ViewGroup =
            mainActivity.findViewById(R.id.iso_buttons) // Shown when Camera API2 enabled
        val exposureSeekBar: View = mainActivity.findViewById(R.id.exposure_container)
        val shutterSeekbar: View = mainActivity.findViewById(R.id.exposure_time_seekbar)
        val isoSeekbar: View = mainActivity.findViewById(R.id.iso_seekbar)
        val wbSeekbar: View = mainActivity.findViewById(R.id.white_balance_seekbar)
        // Set all lines to black
        isoButtonsContainer.setBackgroundColor(Color.TRANSPARENT)
        exposureSeekBar.setBackgroundColor(Color.TRANSPARENT)
        shutterSeekbar.setBackgroundColor(Color.TRANSPARENT)
        isoSeekbar.setBackgroundColor(Color.TRANSPARENT)
        wbSeekbar.setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * Highlights the relevant line on the Exposure UI based on
     * the value of mExposureLine
     *
     */
    private fun highlightExposureUILine(selectNext: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "highlightExposureUILine: $selectNext"
        )
        if (!isExposureUIOpen) { // Safety check
            return
        }
        val isoButtonsContainer: ViewGroup =
            mainActivity.findViewById(R.id.iso_buttons) // Shown when Camera API2 enabled
        val exposureSeekBar: View = mainActivity.findViewById(R.id.exposure_container)
        val shutterSeekbar: View = mainActivity.findViewById(R.id.exposure_time_seekbar)
        val isoSeekbar: View = mainActivity.findViewById(R.id.iso_seekbar)
        val wbSeekbar: View = mainActivity.findViewById(R.id.white_balance_seekbar)
        // Our order for lines is:
        // - ISO buttons
        // - ISO slider
        // - Shutter speed
        // - exposure seek bar
        if (MyDebug.LOG) Log.d(TAG, "mExposureLine: $mExposureLine")
        mExposureLine = (mExposureLine + 5) % 5
        if (MyDebug.LOG) Log.d(
            TAG,
            "mExposureLine modulo: $mExposureLine"
        )
        if (selectNext) {
            if (mExposureLine == 0 && !isoButtonsContainer.isShown) mExposureLine++
            if (mExposureLine == 1 && !isoSeekbar.isShown) mExposureLine++
            if (mExposureLine == 2 && !shutterSeekbar.isShown) mExposureLine++
            if ((mExposureLine == 3) && !exposureSeekBar.isShown) mExposureLine++
            if ((mExposureLine == 4) && !wbSeekbar.isShown) mExposureLine++
        } else {
            // Select previous
            if (mExposureLine == 4 && !wbSeekbar.isShown) mExposureLine--
            if (mExposureLine == 3 && !exposureSeekBar.isShown) mExposureLine--
            if (mExposureLine == 2 && !shutterSeekbar.isShown) mExposureLine--
            if (mExposureLine == 1 && !isoSeekbar.isShown) mExposureLine--
            if (mExposureLine == 0 && !isoButtonsContainer.isShown) mExposureLine--
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "after skipping: mExposureLine: $mExposureLine"
        )
        mExposureLine = (mExposureLine + 5) % 5
        if (MyDebug.LOG) Log.d(
            TAG,
            "after skipping: mExposureLine modulo: $mExposureLine"
        )
        resetExposureUIHighlights()

        if (mExposureLine == 0) {
            isoButtonsContainer.setBackgroundColor(highlightColor)
            //iso_buttons_container.setAlpha(0.5f);
        } else if (mExposureLine == 1) {
            isoSeekbar.setBackgroundColor(highlightColor)
            //iso_seekbar.setAlpha(0.5f);
        } else if (mExposureLine == 2) {
            shutterSeekbar.setBackgroundColor(highlightColor)
            //shutter_seekbar.setAlpha(0.5f);
        } else if (mExposureLine == 3) { //
            exposureSeekBar.setBackgroundColor(highlightColor)
            //exposure_seek_bar.setAlpha(0.5f);
        } else if (mExposureLine == 4) {
            wbSeekbar.setBackgroundColor(highlightColor)
            //wb_seekbar.setAlpha(0.5f);
        }
    }

    private fun nextExposureUILine() {
        mExposureLine++
        highlightExposureUILine(true)
    }

    private fun previousExposureUILine() {
        mExposureLine--
        highlightExposureUILine(false)
    }

    /**
     * Our order for lines is:
     * -0: ISO buttons
     * -1: ISO slider
     * -2: Shutter speed
     * -3: exposure seek bar
     */
    private fun nextExposureUIItem() {
        if (MyDebug.LOG) Log.d(TAG, "nextExposureUIItem")
        when (mExposureLine) {
            0 -> nextIsoItem(false)
            1 -> changeSeekbar(R.id.iso_seekbar, 10)
            2 -> changeSeekbar(R.id.exposure_time_seekbar, 5)
            3 ->                 //changeSeekbar(R.id.exposure_seekbar, 1);
                // call via MainActivity.changeExposure(), to handle repeated zeroes
                mainActivity.changeExposure(1)

            4 -> changeSeekbar(R.id.white_balance_seekbar, 3)
        }
    }

    private fun previousExposureUIItem() {
        if (MyDebug.LOG) Log.d(TAG, "previousExposureUIItem")
        when (mExposureLine) {
            0 -> nextIsoItem(true)
            1 -> changeSeekbar(R.id.iso_seekbar, -10)
            2 -> changeSeekbar(R.id.exposure_time_seekbar, -5)
            3 ->                 //changeSeekbar(R.id.exposure_seekbar, -1);
                // call via MainActivity.changeExposure(), to handle repeated zeroes
                mainActivity.changeExposure(-1)

            4 -> changeSeekbar(R.id.white_balance_seekbar, -3)
        }
    }

    private fun nextIsoItem(previous: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "nextIsoItem: $previous")
        // Find current ISO
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val currentIso = sharedPreferences.getString(
            PreferenceKeys.ISO_PREFERENCE_KEY,
            CameraController.ISO_DEFAULT
        )
        val count = isoButtons!!.size
        val step = if (previous) -1 else 1
        var found = false
        for (i in 0..<count) {
            val button = isoButtons!![i] as Button
            val buttonText = button.text.toString()
            if (ISOTextEquals(buttonText, currentIso!!)) {
                found = true
                // Select next one, unless it's "Manual", which we skip since
                // it's not practical in remote mode.
                var nextButton = isoButtons!![(i + count + step) % count] as Button
                val nextButton_text = nextButton.text.toString()
                if (nextButton_text.contains("m")) {
                    nextButton = isoButtons!![(i + count + 2 * step) % count] as Button
                }
                nextButton.callOnClick()
                break
            }
        }
        if (!found) {
            // For instance, we are in ISO manual mode and "M" is selected. default
            // back to "Auto" to avoid being stuck since we're with a remote control
            isoButtons!![0].callOnClick()
        }
    }

    /**
     * Select element on exposure UI. Based on the value of mExposureLine
     * // Our order for lines is:
     * // - ISO buttons
     * // - ISO slider
     * // - Shutter speed
     * // - exposure seek bar
     */
    private fun selectExposureUILine() {
        if (MyDebug.LOG) Log.d(TAG, "selectExposureUILine")
        if (!isExposureUIOpen) { // Safety check
            return
        }

        if (mExposureLine == 0) { // ISO presets
            val isoButtonsContainer: ViewGroup = mainActivity.findViewById(R.id.iso_buttons)
            isoButtonsContainer.setBackgroundColor(highlightColorExposureUIElement)
            //iso_buttons_container.setAlpha(1f);
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            val currentIso = sharedPreferences.getString(
                PreferenceKeys.ISO_PREFERENCE_KEY,
                CameraController.ISO_DEFAULT
            )
            // if the manual ISO value isn't one of the "preset" values, then instead highlight the manual ISO icon
            var found = false
            var manualButton: Button? = null
            for (view in isoButtons!!) {
                val button = view as Button
                val buttonText = button.text.toString()
                if (ISOTextEquals(buttonText, currentIso!!)) {
                    PopupView.setButtonSelected(button, true)
                    //button.setBackgroundColor(highlightColorExposureUIElement);
                    //button.setAlpha(0.3f);
                    found = true
                } else {
                    if (buttonText.contains("m")) {
                        manualButton = button
                    }
                    PopupView.setButtonSelected(button, false)
                    button.setBackgroundColor(Color.TRANSPARENT)
                }
            }
            if (!found && manualButton != null) {
                // We are in manual ISO, highlight the "M" button
                PopupView.setButtonSelected(manualButton, true)
                manualButton.setBackgroundColor(highlightColorExposureUIElement)
                //manualButton.setAlpha(0.3f);
            }
            mSelectingExposureUIElement = true
        } else if (mExposureLine == 1) {
            // ISO seek bar - change color
            val seekBar: View = mainActivity.findViewById(R.id.iso_seekbar)
            //seek_bar.setAlpha(0.1f);
            seekBar.setBackgroundColor(highlightColorExposureUIElement)
            mSelectingExposureUIElement = true
        } else if (mExposureLine == 2) {
            // ISO seek bar - change color
            val seekBar: View = mainActivity.findViewById(R.id.exposure_time_seekbar)
            //seek_bar.setAlpha(0.1f);
            seekBar.setBackgroundColor(highlightColorExposureUIElement)
            mSelectingExposureUIElement = true
        } else if (mExposureLine == 3) {
            // Exposure compensation
            val container: View = mainActivity.findViewById(R.id.exposure_container)
            //container.setAlpha(0.1f);
            container.setBackgroundColor(highlightColorExposureUIElement)
            mSelectingExposureUIElement = true
        } else if (mExposureLine == 4) {
            // Manual white balance
            val container: View = mainActivity.findViewById(R.id.white_balance_seekbar)
            //container.setAlpha(0.1f);
            container.setBackgroundColor(highlightColorExposureUIElement)
            mSelectingExposureUIElement = true
        }
    }

    /** Returns the height of the device in dp (or width in portrait mode), allowing for space for the
     * on-screen UI icons.
     * @param centred If true, then find the max height for a view that will be centred.
     */
    fun getMaxHeightDp(centred: Boolean): Int {
        // ensure we have display for landscape orientation (even if we ever allow Open Kamera
        val displaySize = Point()
        mainActivity.applicationInterface.getDisplaySize(displaySize, true)

        // normally we should always have heightPixels < widthPixels, but good not to assume we're running in landscape orientation
        val smallerDim = min(displaySize.x.toDouble(), displaySize.y.toDouble()).toInt()
        // the smaller dimension should limit the width, due to when held in portrait
        val scale: Float = mainActivity.getResources().getDisplayMetrics().density
        var dpHeight = (smallerDim / scale).toInt()
        if (MyDebug.LOG) {
            Log.d(TAG, "display size: " + displaySize.x + " x " + displaySize.y)
            Log.d(TAG, "dpHeight: $dpHeight")
        }
        // allow space for the icons at top/right of screen
        val margin = if (centred) 120 else 50
        dpHeight -= margin
        return dpHeight
    }

    val isSelectingExposureUIElement: Boolean
        get() {
            if (MyDebug.LOG) Log.d(
                TAG,
                "isSelectingExposureUIElement returns:$mSelectingExposureUIElement"
            )
            return mSelectingExposureUIElement
        }


    /**
     * Process a press to the "Up" button on a remote. Called from MainActivity.
     * @return true if an action was taken
     */
    fun processRemoteUpButton(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "processRemoteUpButton")
        var didProcess = false
        if (popupIsOpen()) {
            didProcess = true
            if (selectingIcons()) {
                previousPopupIcon()
            } else if (selectingLines()) {
                previousPopupLine()
            }
        } else if (isExposureUIOpen) {
            didProcess = true
            if (isSelectingExposureUIElement) {
                nextExposureUIItem()
            } else {
                previousExposureUILine()
            }
        }
        return didProcess
    }

    /**
     * Process a press to the "Down" button on a remote. Called from MainActivity.
     * @return true if an action was taken
     */
    fun processRemoteDownButton(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "processRemoteDownButton")
        var didProcess = false
        if (popupIsOpen()) {
            if (selectingIcons()) {
                nextPopupIcon()
            } else if (selectingLines()) {
                nextPopupLine()
            }
            didProcess = true
        } else if (isExposureUIOpen) {
            if (isSelectingExposureUIElement) {
                previousExposureUIItem()
            } else {
                nextExposureUILine()
            }
            didProcess = true
        }
        return didProcess
    }

    private var isoButtons: List<View>? = null
    private var isoButtonManualIndex = -1

    init {
        if (MyDebug.LOG) Log.d(TAG, "MainUI")

        this.setSeekbarColors()
    }

    /** Opens the exposure UI if not already open, and sets up or updates the UI.
     */
    fun setupExposureUI() {
        if (MyDebug.LOG) Log.d(TAG, "setupExposureUI")
        testUiButtons.clear()
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        val preview: Preview = mainActivity.preview
        val view: ImageButton = mainActivity.findViewById(R.id.exposure)
        view.setImageResource(R.drawable.ic_exposure_red_48dp)
        val slidersContainer: View = mainActivity.findViewById(R.id.sliders_container)
        slidersContainer.visibility = View.VISIBLE
        val animation = AnimationUtils.loadAnimation(mainActivity, R.anim.fade_in)
        slidersContainer.startAnimation(animation)
        val isoButtonsContainer: ViewGroup = mainActivity.findViewById(R.id.iso_buttons)
        isoButtonsContainer.removeAllViews()
        val supportedIsos: List<String>?
        if (preview.isVideoRecording) {
            supportedIsos = null
        } else if (preview.supportsISORange()) {
            if (MyDebug.LOG) Log.d(TAG, "supports ISO range")
            val minIso: Int = preview.minimumISO
            val maxIso: Int = preview.maximumISO
            val values: MutableList<String> = ArrayList()
            values.add(CameraController.ISO_DEFAULT)
            values.add(manualIsoValue)
            isoButtonManualIndex = 1 // must match where we place the manual button!
            val isoValues = intArrayOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
            values.add(ISOToButtonText(minIso))
            for (isoValue in isoValues) {
                if (isoValue > minIso && isoValue < maxIso) {
                    values.add(ISOToButtonText(isoValue))
                }
            }
            values.add(ISOToButtonText(maxIso))
            supportedIsos = values.toList()
        } else {
            supportedIsos = preview.supportedISOs
            isoButtonManualIndex = -1
        }
        var currentIso = sharedPreferences.getString(
            PreferenceKeys.ISO_PREFERENCE_KEY,
            CameraController.ISO_DEFAULT
        )
        // if the manual ISO value isn't one of the "preset" values, then instead highlight the manual ISO icon
        if ((currentIso != CameraController.ISO_DEFAULT) && supportedIsos != null && supportedIsos.contains(
                manualIsoValue
            ) && !supportedIsos.contains(currentIso)
        ) currentIso = manualIsoValue


        var totalWidthDp = 280
        val maxWidthDp = getMaxHeightDp(true)
        if (totalWidthDp > maxWidthDp) totalWidthDp = maxWidthDp
        if (MyDebug.LOG) Log.d(TAG, "total_width_dp: $totalWidthDp")

        // n.b., we hardcode the string "ISO" as this isn't a user displayed string, rather it's used to filter out "ISO" included in old Camera API parameters
        isoButtons = PopupView.createButtonOptions(
            isoButtonsContainer,
            mainActivity,
            totalWidthDp,
            testUiButtons,
            supportedIsos,
            -1,
            -1,
            "ISO",
            false,
            currentIso,
            0,
            "TEST_ISO",
            object : PopupView.ButtonOptionsPopupListener() {
                override fun onClick(option: String) {
                    if (MyDebug.LOG) Log.d(TAG, "clicked iso: $option")
                    val editor = sharedPreferences.edit()
                    val oldIso = sharedPreferences.getString(
                        PreferenceKeys.ISO_PREFERENCE_KEY,
                        CameraController.ISO_DEFAULT
                    )
                    if (MyDebug.LOG) Log.d(TAG, "old_iso: $oldIso")
                    editor.putString(PreferenceKeys.ISO_PREFERENCE_KEY, option)
                    var toastOption = option

                    if (preview.supportsISORange()) {
                        if (option == CameraController.ISO_DEFAULT) {
                            if (MyDebug.LOG) Log.d(TAG, "switched from manual to auto iso")
                            // also reset exposure time when changing from manual to auto from the popup menu:
                            editor.putLong(
                                PreferenceKeys.EXPOSURE_TIME_PREFERENCE_KEY,
                                CameraController.EXPOSURE_TIME_DEFAULT
                            )
                            editor.apply()
                            preview.showToast(
                                null,
                                "ISO: $toastOption",
                                0,
                                true
                            ) // supply offsetYDp to be consistent with preview.setExposure(), preview.setISO()
                            mainActivity.updateForSettings(
                                true,
                                ""
                            ) // already showed the toast, so block from showing again
                        } else if (oldIso == CameraController.ISO_DEFAULT) {
                            if (MyDebug.LOG) Log.d(TAG, "switched from auto to manual iso")
                            if (option == "m") {
                                // if we used the generic "manual", then instead try to preserve the current iso if it exists
                                if (preview.cameraController != null && preview.cameraController!!.captureResultHasIso()) {
                                    val iso: Int = preview.cameraController!!.captureResultIso()
                                    if (MyDebug.LOG) Log.d(
                                        TAG,
                                        "apply existing iso of $iso"
                                    )
                                    editor.putString(
                                        PreferenceKeys.ISO_PREFERENCE_KEY,
                                        iso.toString()
                                    )
                                    toastOption = iso.toString()
                                } else {
                                    if (MyDebug.LOG) Log.d(TAG, "no existing iso available")
                                    // use a default
                                    val iso = 800
                                    editor.putString(PreferenceKeys.ISO_PREFERENCE_KEY, "" + iso)
                                    toastOption = "" + iso
                                }
                            }

                            // if changing from auto to manual, preserve the current exposure time if it exists
                            if (preview.cameraController != null && preview.cameraController!!.captureResultHasExposureTime()) {
                                val exposureTime: Long =
                                    preview.cameraController!!.captureResultExposureTime()
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "apply existing exposure time of $exposureTime"
                                )
                                editor.putLong(
                                    PreferenceKeys.EXPOSURE_TIME_PREFERENCE_KEY,
                                    exposureTime
                                )
                            } else {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "no existing exposure time available"
                                )
                            }

                            editor.apply()
                            preview.showToast(
                                null,
                                "ISO: $toastOption",
                                0,
                                true
                            ) // supply offsetYDp to be consistent with preview.setExposure(), preview.setISO()
                            mainActivity.updateForSettings(
                                true,
                                ""
                            ) // already showed the toast, so block from showing again
                        } else {
                            if (MyDebug.LOG) Log.d(TAG, "changed manual iso")
                            if (option == "m") {
                                // if user selected the generic "manual", then just keep the previous non-ISO option
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "keep existing iso of $oldIso"
                                )
                                editor.putString(PreferenceKeys.ISO_PREFERENCE_KEY, oldIso)
                            }

                            editor.apply()
                            val iso: Int = preview.parseManualISOValue(option)
                            if (iso >= 0) {
                                // if changing between manual ISOs, no need to call updateForSettings, just change the ISO directly (as with changing the ISO via manual slider)
                                //preview.setISO(iso);
                                //updateSelectedISOButton();
                                // rather than set ISO directly, we move the seekbar, and the ISO will be changed via the seekbar listener
                                val isoSeekBar: SeekBar =
                                    mainActivity.findViewById(R.id.iso_seekbar)
                                mainActivity.manualSeekbars
                                    .setISOProgressBarToClosest(isoSeekBar, iso.toLong())
                            }
                        }
                    } else {
                        editor.apply()
                        if (preview.cameraController != null) {
                            preview.cameraController!!.setISO(option)
                        }
                    }

                    setupExposureUI()
                }
            })
        if (supportedIsos != null) {
            val isoContainerView: View = mainActivity.findViewById(R.id.iso_container)
            isoContainerView.visibility = View.VISIBLE
        }

        val exposureSeekBar: View = mainActivity.findViewById(R.id.exposure_container)
        val manualExposureSeekBar: View =
            mainActivity.findViewById(R.id.manual_exposure_container)
        val isoValue: String = mainActivity.applicationInterface.getISOPref()
        if (mainActivity.preview
                .usingCamera2API() && isoValue != CameraController.ISO_DEFAULT
        ) {
            exposureSeekBar.visibility = View.GONE

            // with Camera2 API, when using manual ISO we instead show sliders for ISO range and exposure time
            if (mainActivity.preview.supportsISORange()) {
                manualExposureSeekBar.visibility = View.VISIBLE
                val exposureTimeSeekBar: SeekBar =
                    mainActivity.findViewById(R.id.exposure_time_seekbar)
                if (mainActivity.preview.supportsExposureTime()) {
                    exposureTimeSeekBar.visibility = View.VISIBLE
                } else {
                    exposureTimeSeekBar.visibility = View.GONE
                }
            } else {
                manualExposureSeekBar.visibility = View.GONE
            }
        } else {
            manualExposureSeekBar.visibility = View.GONE

            if (mainActivity.preview.supportsExposures()) {
                exposureSeekBar.visibility = View.VISIBLE
            } else {
                exposureSeekBar.visibility = View.GONE
            }
        }

        val manualWhiteBalanceSeekBar: View =
            mainActivity.findViewById(R.id.manual_white_balance_container)
        if (mainActivity.preview.supportsWhiteBalanceTemperature()) {
            // we also show slider for manual white balance, if in that mode
            val whiteBalanceValue: String =
                mainActivity.applicationInterface.getWhiteBalancePref()
            if (mainActivity.preview.usingCamera2API() && whiteBalanceValue == "manual") {
                manualWhiteBalanceSeekBar.visibility = View.VISIBLE
            } else {
                manualWhiteBalanceSeekBar.visibility = View.GONE
            }
        } else {
            manualWhiteBalanceSeekBar.visibility = View.GONE
        }

        //layoutUI(); // needed to update alignment of exposure UI
    }

    /** If the exposure panel is open, updates the selected ISO button to match the current ISO value,
     * if a continuous range of ISO values are supported by the camera.
     */
    fun updateSelectedISOButton() {
        if (MyDebug.LOG) Log.d(TAG, "updateSelectedISOButton")
        val preview: Preview = mainActivity.preview
        if (preview.supportsISORange() && isExposureUIOpen) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            val currentIso = sharedPreferences.getString(
                PreferenceKeys.ISO_PREFERENCE_KEY,
                CameraController.ISO_DEFAULT
            )
            // if the manual ISO value isn't one of the "preset" values, then instead highlight the manual ISO icon
            if (MyDebug.LOG) Log.d(TAG, "current_iso: $currentIso")
            var found = false
            for (view in isoButtons!!) {
                val button = view as Button
                if (MyDebug.LOG) Log.d(TAG, "button: " + button.text)
                val buttonText = button.text.toString()
                if (ISOTextEquals(buttonText, currentIso!!)) {
                    PopupView.setButtonSelected(button, true)
                    found = true
                } else {
                    PopupView.setButtonSelected(button, false)
                }
            }
            if (!found && currentIso != CameraController.ISO_DEFAULT) {
                if (MyDebug.LOG) Log.d(TAG, "must be manual")
                if (isoButtonManualIndex >= 0 && isoButtonManualIndex < isoButtons!!.size) {
                    val button = isoButtons!![isoButtonManualIndex] as Button
                    PopupView.setButtonSelected(button, true)
                }
            }
        }
    }

    fun setSeekbarZoom(newZoom: Int) {
        if (MyDebug.LOG) Log.d(TAG, "setSeekbarZoom: $newZoom")
        val zoomSeekBar: SeekBar = mainActivity.findViewById(R.id.zoom_seekbar)
        if (MyDebug.LOG) Log.d(TAG, "progress was: " + zoomSeekBar.progress)
        zoomSeekBar.progress = mainActivity.preview.maxZoom - newZoom
        if (MyDebug.LOG) Log.d(TAG, "progress is now: " + zoomSeekBar.progress)
    }

    fun changeSeekbar(seekBarId: Int, change: Int) {
        if (MyDebug.LOG) Log.d(TAG, "changeSeekbar: $change")
        val seekBar: SeekBar = mainActivity.findViewById(seekBarId)
        val value = seekBar.progress
        var newValue = value + change
        if (newValue < 0) newValue = 0
        else if (newValue > seekBar.max) newValue = seekBar.max
        if (MyDebug.LOG) {
            Log.d(TAG, "value: $value")
            Log.d(TAG, "new_value: $newValue")
            Log.d(TAG, "max: " + seekBar.max)
        }
        if (newValue != value) {
            seekBar.progress = newValue
        }
    }

    /** Closes the exposure UI.
     */
    fun closeExposureUI() {
        val imageButton: ImageButton = mainActivity.findViewById(R.id.exposure)
        imageButton.setImageResource(R.drawable.ic_exposure_white_48dp)

        clearRemoteControlForExposureUI() // must be called before we actually close the exposure panel
        var view: View = mainActivity.findViewById(R.id.sliders_container)
        view.visibility = View.GONE
        view = mainActivity.findViewById(R.id.iso_container)
        view.visibility = View.GONE
        view = mainActivity.findViewById(R.id.exposure_container)
        view.visibility = View.GONE
        view = mainActivity.findViewById(R.id.manual_exposure_container)
        view.visibility = View.GONE
        view = mainActivity.findViewById(R.id.manual_white_balance_container)
        view.visibility = View.GONE
    }

    fun setPopupIcon() {
        if (MyDebug.LOG) Log.d(TAG, "setPopupIcon")
        val popup: ImageButton = mainActivity.findViewById(R.id.popup)
        val flashValue: String? = mainActivity.preview.currentFlashValue
        if (MyDebug.LOG) Log.d(TAG, "flash_value: $flashValue")
        if (mainActivity.mainUI.showCycleFlashIcon()) {
            popup.setImageResource(R.drawable.popup)
        } else if (flashValue != null && flashValue == "flash_off") {
            popup.setImageResource(R.drawable.popup_flash_off)
        } else if (flashValue != null && (flashValue == "flash_torch" || flashValue == "flash_frontscreen_torch")) {
            popup.setImageResource(R.drawable.popup_flash_torch)
        } else if (flashValue != null && (flashValue == "flash_auto" || flashValue == "flash_frontscreen_auto")) {
            popup.setImageResource(R.drawable.popup_flash_auto)
        } else if (flashValue != null && (flashValue == "flash_on" || flashValue == "flash_frontscreen_on")) {
            popup.setImageResource(R.drawable.popup_flash_on)
        } else if (flashValue != null && flashValue == "flash_red_eye") {
            popup.setImageResource(R.drawable.popup_flash_red_eye)
        } else {
            popup.setImageResource(R.drawable.popup)
        }
    }

    fun closePopup() {
        if (MyDebug.LOG) Log.d(TAG, "close popup")

        mainActivity.enablePopupOnBackPressedCallback(false)

        if (popupIsOpen()) {
            clearRemoteControlForPopup() // must be called before we set popupViewIsOpen to false; and before clearSelectionState() so we know which highlighting to disable
            clearSelectionState()

            popupViewIsOpen = false
            /* Not destroying the popup doesn't really gain any performance.
             * Also there are still outstanding bugs to fix if we wanted to do this:
             *   - Not resetting the popup menu when switching between photo and video mode. See test testVideoPopup().
             *   - When changing options like flash/focus, the new option isn't selected when reopening the popup menu. See test
             *     testPopup().
             *   - Changing settings potentially means we have to recreate the popup, so the natural place to do this is in
             *     MainActivity.updateForSettings(), but doing so makes the popup close when checking photo or video resolutions!
             *     See test testSwitchResolution().
             */
            if (cachePopup && !forceDestroyPopup) {
                popupView?.visibility = View.GONE
            } else {
                destroyPopup()
            }
            mainActivity.initImmersiveMode() // to reset the timer when closing the popup
        }
    }

    fun popupIsOpen(): Boolean {
        return popupViewIsOpen
    }

    fun selectingIcons(): Boolean {
        return mSelectingIcons
    }

    fun selectingLines(): Boolean {
        return mSelectingLines
    }

    fun destroyPopup() {
        if (MyDebug.LOG) Log.d(TAG, "destroyPopup")
        forceDestroyPopup = false
        if (popupIsOpen()) {
            closePopup()
        }
        val popupContainer: ViewGroup = mainActivity.findViewById(R.id.popup_container)
        popupContainer.removeAllViews()
        popupView = null
    }

    /**
     * Higlights the next LinearLayout view
     */
    private fun highlightPopupLine(highlight: Boolean, goUp: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "highlightPopupLine")
            Log.d(TAG, "highlight: $highlight")
            Log.d(TAG, "goUp: $goUp")
        }
        if (!popupIsOpen()) { // Safety check
            clearSelectionState()
            return
        }
        val popupContainer: ViewGroup = mainActivity.findViewById(R.id.popup_container)
        val scrollBounds = Rect()
        popupContainer.getDrawingRect(scrollBounds)
        val inside = popupContainer.getChildAt(0) as LinearLayout ?: return
        // Safety check

        val count = inside.childCount
        var foundLine = false
        while (!foundLine) {
            // Ensure we stay within our bounds:
            mPopupLine = (mPopupLine + count) % count
            var v = inside.getChildAt(mPopupLine)
            if (MyDebug.LOG) Log.d(TAG, "line: $mPopupLine view: $v")
            // to test example with HorizontalScrollView, see popup menu on Nokia 8 with Camera2 API, the flash icons row uses a HorizontalScrollView
            if (v is HorizontalScrollView && v.childCount > 0) v = v.getChildAt(0)
            if (v.isShown && v is LinearLayout) {
                if (highlight) {
                    v.setBackgroundColor(highlightColor)
                    //v.setAlpha(0.3f);
                    if (v.getBottom() > scrollBounds.bottom || v.getTop() < scrollBounds.top) popupContainer.scrollTo(
                        0,
                        v.getTop()
                    )
                    mHighlightedLine = v
                } else {
                    v.setBackgroundColor(Color.TRANSPARENT)
                    v.setAlpha(1f)
                }
                foundLine = true
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "found at line: $foundLine"
                )
            } else {
                mPopupLine += if (goUp) -1 else 1
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "Current line: $mPopupLine")
    }

    /**
     * Highlights an icon on a horizontal line, such as flash mode,
     * focus mode, etc. Checks that the popup is open in case it is
     * wrongly called, so that it doesn't crash the app.
     */
    private fun highlightPopupIcon(highlight: Boolean, goLeft: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "highlightPopupIcon")
            Log.d(TAG, "highlight: $highlight")
            Log.d(TAG, "goLeft: $goLeft")
        }
        if (!popupIsOpen()) { // Safety check
            clearSelectionState()
            return
        }
        highlightPopupLine(false, false)
        val count = mHighlightedLine!!.childCount
        var foundIcon = false
        while (!foundIcon) {
            // Ensure we stay within our bounds:
            // (careful, modulo in Java will allow negative numbers, hence the line below:
            mPopupIcon = (mPopupIcon + count) % count
            val v = mHighlightedLine!!.getChildAt(mPopupIcon)
            if (MyDebug.LOG) Log.d(TAG, "row: $mPopupIcon view: $v")
            if (v is ImageButton || v is Button) {
                if (highlight) {
                    v.setBackgroundColor(highlightColor)
                    //v.setAlpha(0.5f);
                    mHighlightedIcon = v
                    mSelectingIcons = true
                } else {
                    v.setBackgroundColor(Color.TRANSPARENT)
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "found icon at row: $mPopupIcon"
                )
                foundIcon = true
            } else {
                mPopupIcon += if (goLeft) -1 else 1
            }
        }
    }

    /**
     * Select the next line on the settings popup. Called by MainActivity
     * when receiving a remote control command.
     */
    private fun nextPopupLine() {
        highlightPopupLine(false, false)
        mPopupLine++
        highlightPopupLine(true, false)
    }

    private fun previousPopupLine() {
        highlightPopupLine(false, true)
        mPopupLine--
        highlightPopupLine(true, true)
    }

    private fun nextPopupIcon() {
        highlightPopupIcon(false, false)
        mPopupIcon++
        highlightPopupIcon(true, false)
    }

    private fun previousPopupIcon() {
        highlightPopupIcon(false, true)
        mPopupIcon--
        highlightPopupIcon(true, true)
    }

    /**
     * Simulates a press on the currently selected icon
     */
    private fun clickSelectedIcon() {
        if (MyDebug.LOG) Log.d(
            TAG,
            "clickSelectedIcon: $mHighlightedIcon"
        )
        if (mHighlightedIcon != null) {
            mHighlightedIcon!!.callOnClick()
        }
    }

    /**
     * Ensure all our selection tracking variables are cleared when we
     * exit menu selection (used in remote control mode)
     */
    private fun clearSelectionState() {
        if (MyDebug.LOG) Log.d(TAG, "clearSelectionState")
        mPopupLine = 0
        mPopupIcon = 0
        mSelectingIcons = false
        mSelectingLines = false
        mHighlightedIcon = null
        mHighlightedLine = null
    }

    /**
     * Opens or closes the settings popup on the camera preview. The popup that
     * differs depending whether we're in photo or video mode
     */
    fun togglePopupSettings() {
        val popupContainer: ViewGroup = mainActivity.findViewById(R.id.popup_container)
        if (popupIsOpen()) {
            closePopup()
            return
        }
        if (mainActivity.preview.cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }

        if (MyDebug.LOG) Log.d(TAG, "open popup")

        mainActivity.enablePopupOnBackPressedCallback(true) // so that back button will close the popup instead of exiting the application

        closeExposureUI()
        mainActivity.preview
            .cancelTimer() // best to cancel any timer, in case we take a photo while settings window is open, or when changing settings
        mainActivity.stopAudioListeners()

        val timeS = System.currentTimeMillis()

        run {
            // prevent popup being transparent
            popupContainer.setBackgroundColor(Color.BLACK)
            popupContainer.alpha = 0.9f
        }

        if (popupView == null) {
            if (MyDebug.LOG) Log.d(TAG, "create new popup_view")
            testUiButtons.clear()
            popupView = PopupView(mainActivity)
            popupContainer.addView(popupView)
        } else {
            if (MyDebug.LOG) Log.d(TAG, "use cached popup_view")
            popupView!!.visibility = View.VISIBLE
        }
        popupViewIsOpen = true

        if (mainActivity.bluetoothRemoteControl.remoteEnabled()) {
            initRemoteControlForPopup()
        }

        // need to call layoutUI to make sure the new popup is oriented correctly
        // but need to do after the layout has been done, so we have a valid width/height to use
        // n.b., even though we only need the portion of layoutUI for the popup container, there
        // doesn't seem to be any performance benefit in only calling that part
        popupContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (MyDebug.LOG) Log.d(TAG, "onGlobalLayout()")
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "time after global layout: " + (System.currentTimeMillis() - timeS)
                    )
                    layoutUI(true)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "time after layoutUI: " + (System.currentTimeMillis() - timeS)
                    )
                    // stop listening - only want to call this once!
                    popupContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    val uiPlacement = computeUIPlacement()
                    val systemOrientation: SystemOrientation = mainActivity.systemOrientation
                    val pivotX: Float
                    val pivotY: Float
                    when (uiPlacement) {
                        UIPlacement.UIPLACEMENT_TOP -> if (mainActivity.preview
                                .uIRotation === 270
                        ) {
                            // portrait (when not locked)
                            pivotX = 0.0f
                            pivotY = 1.0f
                        } else if (systemOrientation === SystemOrientation.REVERSE_LANDSCAPE) {
                            pivotX = 1.0f
                            pivotY = 1.0f
                        } else {
                            pivotX = 0.0f
                            pivotY = 0.0f
                        }

                        UIPlacement.UIPLACEMENT_LEFT -> if (systemOrientation === SystemOrientation.PORTRAIT) {
                            pivotX = 0.0f
                            pivotY = 1.0f
                        } else if (systemOrientation === SystemOrientation.REVERSE_LANDSCAPE) {
                            pivotX = 0.0f
                            pivotY = 0.0f
                        } else {
                            pivotX = 1.0f
                            pivotY = 1.0f
                        }

                        else -> if (systemOrientation === SystemOrientation.PORTRAIT) {
                            pivotX = 1.0f
                            pivotY = 1.0f
                        } else if (systemOrientation === SystemOrientation.REVERSE_LANDSCAPE) {
                            pivotX = 0.0f
                            pivotY = 1.0f
                        } else {
                            pivotX = 1.0f
                            pivotY = 0.0f
                        }
                    }
                    val animation = ScaleAnimation(
                        0.0f,
                        1.0f,
                        0.0f,
                        1.0f,
                        Animation.RELATIVE_TO_SELF,
                        pivotX,
                        Animation.RELATIVE_TO_SELF,
                        pivotY
                    )
                    animation.duration = 200
                    //popup_container.setAnimation(animation);
                    val fadeAnimation = AlphaAnimation(0.0f, 1.0f)
                    fadeAnimation.duration = 200
                    val animationSet = AnimationSet(false)
                    animationSet.addAnimation(animation)
                    animationSet.addAnimation(fadeAnimation)
                    popupContainer.animation = animationSet
                }
            }
        )

        if (MyDebug.LOG) Log.d(
            TAG,
            "time to create popup: " + (System.currentTimeMillis() - timeS)
        )
    }

    private fun initRemoteControlForPopup() {
        if (MyDebug.LOG) Log.d(TAG, "initRemoteControlForPopup")
        if (popupIsOpen()) { // just in case
            // For remote control, we want to highlight lines and icons on the popup view
            // so that we can control those just with the up/down buttons and "OK"
            clearSelectionState()
            remoteControlMode = true
            mSelectingLines = true
            highlightPopupLine(true, false)
        }
    }

    private fun clearRemoteControlForPopup() {
        if (MyDebug.LOG) Log.d(TAG, "clearRemoteControlForPopup")
        if (popupIsOpen() && remoteControlMode) {
            remoteControlMode = false

            // reset highlighting
            val popupContainer: ViewGroup = mainActivity.findViewById(R.id.popup_container)
            val scrollBounds = Rect()
            popupContainer.getDrawingRect(scrollBounds)
            val inside = popupContainer.getChildAt(0) as LinearLayout ?: return
            // Safety check

            var v = inside.getChildAt(mPopupLine)
            if (v.isShown && v is LinearLayout) {
                if (MyDebug.LOG) Log.d(TAG, "reset " + mPopupLine + "th view: " + v)
                v.setBackgroundColor(Color.TRANSPARENT)
                v.setAlpha(1f)
            }
            if (mHighlightedLine != null) {
                v = mHighlightedLine!!.getChildAt(mPopupIcon)
                if (v is ImageButton || v is Button) {
                    v.setBackgroundColor(Color.TRANSPARENT)
                }
            }
            /*for(int i=0;i<inside.getChildCount();i++) {
                View v = inside.getChildAt(i);
                if( v.isShown() && v instanceof LinearLayout ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "reset " + i + "th view: " + v);
                    v.setBackgroundColor(Color.TRANSPARENT);
                    v.setAlpha(1f);
                }
            }*/
        }
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onKeyDown: $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) keydownVolumeUp = true
                else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) keydownVolumeDown = true

                val sharedPreferences =
                    PreferenceManager.getDefaultSharedPreferences(mainActivity)
                val volumeKeys =
                    sharedPreferences.getString(
                        PreferenceKeys.VOLUME_KEYS_PREFERENCE_KEY,
                        "volume_take_photo"
                    )!!

                if ((keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_STOP)
                    && volumeKeys != "volume_take_photo"
                ) {
                    val audioManager =
                        mainActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (!audioManager.isWiredHeadsetOn) return false // isWiredHeadsetOn() is deprecated, but comment says "Use only to check is a headset is connected or not."
                }

                when (volumeKeys) {
                    "volume_take_photo" -> {
                        var done = false
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mainActivity.preview
                                .isVideoRecording
                        ) {
                            done = true
                            mainActivity.pauseVideo()
                        }
                        if (!done) {
                            mainActivity.takePicture(false)
                        }
                        return true
                    }

                    "volume_focus" -> {
                        if (keydownVolumeUp && keydownVolumeDown) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "take photo rather than focus, as both volume keys are down"
                            )
                            mainActivity.takePicture(false)
                        } else if (mainActivity.preview.currentFocusValue != null
                            && mainActivity.preview.currentFocusValue.equals("focus_mode_manual2")
                        ) {
                            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) mainActivity.changeFocusDistance(
                                -1,
                                false
                            )
                            else mainActivity.changeFocusDistance(1, false)
                        } else {
                            // important not to repeatedly request focus, even though main_activity.preview.requestAutoFocus() will cancel, as causes problem if key is held down (e.g., flash gets stuck on)
                            // also check DownTime vs EventTime to prevent repeated focusing whilst the key is held down
                            if (event.downTime == event.eventTime && !mainActivity.preview.isFocusWaiting) {
                                if (MyDebug.LOG) Log.d(TAG, "request focus due to volume key")
                                mainActivity.preview.requestAutoFocus()
                            }
                        }
                        return true
                    }

                    "volume_zoom" -> {
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) mainActivity.zoomIn()
                        else mainActivity.zoomOut()
                        return true
                    }

                    "volume_exposure" -> {
                        if (mainActivity.preview.cameraController != null) {
                            val value = sharedPreferences.getString(
                                PreferenceKeys.ISO_PREFERENCE_KEY,
                                CameraController.ISO_DEFAULT
                            )
                            val manualIso = value != CameraController.ISO_DEFAULT
                            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                                if (manualIso) {
                                    mainActivity.changeISO(1)
                                } else mainActivity.changeExposure(1)
                            } else {
                                if (manualIso) {
                                    mainActivity.changeISO(-1)
                                } else mainActivity.changeExposure(-1)
                            }
                        }
                        return true
                    }

                    "volume_auto_stabilise" -> {
                        if (mainActivity.supportsAutoStabilise()) {
                            var autoStabilise = sharedPreferences.getBoolean(
                                PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY,
                                false
                            )
                            autoStabilise = !autoStabilise
                            val editor = sharedPreferences.edit()
                            editor.putBoolean(
                                PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY,
                                autoStabilise
                            )
                            editor.apply()
                            val message: String = mainActivity.getResources()
                                .getString(R.string.preference_auto_stabilise) + ": " + mainActivity.getResources()
                                .getString(if (autoStabilise) R.string.on else R.string.off)
                            mainActivity.preview.showToast(
                                mainActivity.changedAutoStabiliseToastBoxer,
                                message,
                                true
                            )
                            mainActivity.applicationInterface.drawPreview
                                .updateSettings() // because we cache the auto-stabilise setting
                            this.destroyPopup() // need to recreate popup in order to update the auto-level checkbox
                        } else if (!mainActivity.deviceSupportsAutoStabilise()) {
                            // n.b., need to check deviceSupportsAutoStabilise() - if we're in e.g. Panorama mode, we shouldn't display a toast (as then supportsAutoStabilise() returns false even if auto-level is supported on the device)
                            mainActivity.preview.showToast(
                                mainActivity.changedAutoStabiliseToastBoxer,
                                R.string.auto_stabilise_not_supported
                            )
                        }
                        return true
                    }

                    "volume_really_nothing" ->                         // do nothing, but still return true so we don't change volume either
                        return true
                }
            }

            KeyEvent.KEYCODE_MENU -> {
                // needed to support hardware menu button
                // tested successfully on Samsung S3 (via RTL)
                // see http://stackoverflow.com/questions/8264611/how-to-detect-when-user-presses-menu-key-on-their-android-device
                mainActivity.openSettings()
                return true
            }

            KeyEvent.KEYCODE_CAMERA -> {
                run {
                    if (event.repeatCount == 0) {
                        mainActivity.takePicture(false)
                        return true
                    }
                }
                run {
                    // important not to repeatedly request focus, even though main_activity.preview.requestAutoFocus() will cancel - causes problem with hardware camera key where a half-press means to focus
                    // also check DownTime vs EventTime to prevent repeated focusing whilst the key is held down - see https://sourceforge.net/p/OpenKamera/tickets/174/ ,
                    // or same issue above for volume key focus
                    if (event.downTime == event.eventTime && !mainActivity.preview.isFocusWaiting) {
                        if (MyDebug.LOG) Log.d(TAG, "request focus due to focus key")
                        mainActivity.preview.requestAutoFocus()
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_FOCUS -> {
                if (event.downTime == event.eventTime && !mainActivity.preview.isFocusWaiting) {
                    if (MyDebug.LOG) Log.d(TAG, "request focus due to focus key")
                    mainActivity.preview.requestAutoFocus()
                }
                return true
            }

            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                mainActivity.zoomIn()
                return true
            }

            KeyEvent.KEYCODE_ZOOM_OUT, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                mainActivity.zoomOut()
                return true
            }

            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_NUMPAD_5 -> {
                if (isExposureUIOpen && remoteControlMode) {
                    commandMenuExposure()
                    return true
                } else if (popupIsOpen() && remoteControlMode) {
                    commandMenuPopup()
                    return true
                } else if (event.repeatCount == 0) {
                    mainActivity.takePicture(false)
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_NUMPAD_8 ->                 //case KeyEvent.KEYCODE_VOLUME_UP: // test
                if (!remoteControlMode) {
                    if (popupIsOpen()) {
                        initRemoteControlForPopup()
                        return true
                    } else if (isExposureUIOpen) {
                        initRemoteControlForExposureUI()
                        return true
                    }
                } else if (processRemoteUpButton()) return true

            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_NUMPAD_2 ->                 //case KeyEvent.KEYCODE_VOLUME_DOWN: // test
                if (!remoteControlMode) {
                    if (popupIsOpen()) {
                        initRemoteControlForPopup()
                        return true
                    } else if (isExposureUIOpen) {
                        initRemoteControlForExposureUI()
                        return true
                    }
                } else if (processRemoteDownButton()) return true

            KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> togglePopupSettings()
            KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> toggleExposureUI()
        }
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?) {
        if (MyDebug.LOG) Log.d(TAG, "onKeyUp: $keyCode")
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) keydownVolumeUp = false
        else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) keydownVolumeDown = false
    }

    /** If the exposure menu is open, selects a current line or option. Else does nothing.
     */
    fun commandMenuExposure() {
        if (MyDebug.LOG) Log.d(TAG, "commandMenuExposure")
        if (isExposureUIOpen) {
            if (isSelectingExposureUIElement) {
                // Close Exposure UI if new press on MENU
                // while already selecting
                toggleExposureUI()
            } else {
                // Select current element in Exposure UI
                selectExposureUILine()
            }
        }
    }

    /** If the popup menu is open, selects a current line or option. Else does nothing.
     */
    fun commandMenuPopup() {
        if (MyDebug.LOG) Log.d(TAG, "commandMenuPopup")
        if (popupIsOpen()) {
            if (selectingIcons()) {
                clickSelectedIcon()
            } else {
                highlightPopupIcon(true, false)
            }
        }
    }

    /** Shows an information dialog, with a button to request not to show again.
     * Note it's up to the caller to check whether the infoPreferenceKey (to not show again) was
     * already set.
     * @param titleId Resource id for title string.
     * @param infoId Resource id for dialog text string.
     * @param infoPreferenceKey Preference key to set in SharedPreferences if the user selects to
     * not show the dialog again.
     * @return The AlertDialog that was created.
     */
    fun showInfoDialog(titleId: Int, infoId: Int, infoPreferenceKey: String?): AlertDialog {
        val alertDialog = AlertDialog.Builder(mainActivity)
        alertDialog.setTitle(titleId)
        if (infoId != 0) alertDialog.setMessage(infoId)
        alertDialog.setPositiveButton(android.R.string.ok, null)
        alertDialog.setNegativeButton(
            R.string.dont_show_again,
            DialogInterface.OnClickListener { dialog, which ->
                if (MyDebug.LOG) Log.d(TAG, "user clicked dont_show_again for info dialog")
                val sharedPreferences =
                    PreferenceManager.getDefaultSharedPreferences(mainActivity)
                val editor = sharedPreferences.edit()
                editor.putBoolean(infoPreferenceKey, true)
                editor.apply()
            })

        //main_activity.showPreview(false);
        //main_activity.setWindowFlagsForSettings(false); // set setLockProtect to false, otherwise if screen is locked, user will need to unlock to see the info dialog!
        val alert = alertDialog.create()
        // AlertDialog.Builder.setOnDismissListener() requires API level 17, so do it this way instead
        alert.setOnDismissListener {
            if (MyDebug.LOG) Log.d(TAG, "info dialog dismissed")
            //main_activity.setWindowFlagsForCamera();
            //main_activity.showPreview(true);
        }
        //main_activity.showAlert(alert);
        alert.show()
        return alert
    }

    /** Returns a (possibly translated) user readable string for a white balance preference value.
     * If the value is not recognised (this can happen for the old Camera API, some devices can
     * have device-specific options), then the received value is returned.
     */
    fun getEntryForWhiteBalance(value: String): String {
        var id = -1
        when (value) {
            CameraController.WHITE_BALANCE_DEFAULT -> id = R.string.white_balance_auto
            "cloudy-daylight" -> id = R.string.white_balance_cloudy
            "daylight" -> id = R.string.white_balance_daylight
            "fluorescent" -> id = R.string.white_balance_fluorescent
            "incandescent" -> id = R.string.white_balance_incandescent
            "shade" -> id = R.string.white_balance_shade
            "twilight" -> id = R.string.white_balance_twilight
            "warm-fluorescent" -> id = R.string.white_balance_warm
            "manual" -> id = R.string.white_balance_manual
            else -> {}
        }
        val entry = if (id != -1) {
            mainActivity.getResources().getString(id)
        } else {
            value
        }
        return entry
    }

    /** Returns a (possibly translated) user readable string for a scene mode preference value.
     * If the value is not recognised (this can happen for the old Camera API, some devices can
     * have device-specific options), then the received value is returned.
     */
    fun getEntryForSceneMode(value: String): String {
        var id = -1
        when (value) {
            "action" -> id = R.string.scene_mode_action
            "barcode" -> id = R.string.scene_mode_barcode
            "beach" -> id = R.string.scene_mode_beach
            "candlelight" -> id = R.string.scene_mode_candlelight
            CameraController.SCENE_MODE_DEFAULT -> id = R.string.scene_mode_auto
            "fireworks" -> id = R.string.scene_mode_fireworks
            "landscape" -> id = R.string.scene_mode_landscape
            "night" -> id = R.string.scene_mode_night
            "night-portrait" -> id = R.string.scene_mode_night_portrait
            "party" -> id = R.string.scene_mode_party
            "portrait" -> id = R.string.scene_mode_portrait
            "snow" -> id = R.string.scene_mode_snow
            "sports" -> id = R.string.scene_mode_sports
            "steadyphoto" -> id = R.string.scene_mode_steady_photo
            "sunset" -> id = R.string.scene_mode_sunset
            "theatre" -> id = R.string.scene_mode_theatre
            else -> {}
        }
        val entry = if (id != -1) {
            mainActivity.getResources().getString(id)
        } else {
            value
        }
        return entry
    }

    /** Returns a (possibly translated) user readable string for a color effect preference value.
     * If the value is not recognised (this can happen for the old Camera API, some devices can
     * have device-specific options), then the received value is returned.
     */
    fun getEntryForColorEffect(value: String): String {
        var id = -1
        when (value) {
            "aqua" -> id = R.string.color_effect_aqua
            "blackboard" -> id = R.string.color_effect_blackboard
            "mono" -> id = R.string.color_effect_mono
            "negative" -> id = R.string.color_effect_negative
            CameraController.COLOR_EFFECT_DEFAULT -> id = R.string.color_effect_none
            "posterize" -> id = R.string.color_effect_posterize
            "sepia" -> id = R.string.color_effect_sepia
            "solarize" -> id = R.string.color_effect_solarize
            "whiteboard" -> id = R.string.color_effect_whiteboard
            else -> {}
        }
        val entry = if (id != -1) {
            mainActivity.getResources().getString(id)
        } else {
            value
        }
        return entry
    }

    /** Returns a (possibly translated) user readable string for an antibanding preference value.
     * If the value is not recognised, then the received value is returned.
     */
    fun getEntryForAntiBanding(value: String): String {
        var id = -1
        when (value) {
            CameraController.ANTIBANDING_DEFAULT -> id = R.string.anti_banding_auto
            "50hz" -> id = R.string.anti_banding_50hz
            "60hz" -> id = R.string.anti_banding_60hz
            "off" -> id = R.string.anti_banding_off
            else -> {}
        }
        val entry = if (id != -1) {
            mainActivity.getResources().getString(id)
        } else {
            value
        }
        return entry
    }

    /** Returns a (possibly translated) user readable string for an noise reduction mode preference value.
     * If the value is not recognised, then the received value is returned.
     * Also used for edge mode.
     */
    fun getEntryForNoiseReductionMode(value: String): String {
        var id = -1
        when (value) {
            CameraController.NOISE_REDUCTION_MODE_DEFAULT -> id =
                R.string.noise_reduction_mode_default

            "off" -> id = R.string.noise_reduction_mode_off
            "minimal" -> id = R.string.noise_reduction_mode_minimal
            "fast" -> id = R.string.noise_reduction_mode_fast
            "high_quality" -> id = R.string.noise_reduction_mode_high_quality
            else -> {}
        }
        val entry = if (id != -1) {
            mainActivity.getResources().getString(id)
        } else {
            value
        }
        return entry
    }

    // for testing
    fun getUIButton(key: String): View? {
        if (MyDebug.LOG) {
            Log.d(TAG, "getPopupButton(" + key + "): " + testUiButtons[key])
            Log.d(TAG, "this: $this")
            Log.d(TAG, "popup_buttons: $testUiButtons")
        }
        return testUiButtons[key]
    }

    val testUIButtonsMap: MutableMap<String, View>
        get() = testUiButtons

    fun testGetRemoteControlMode(): Boolean {
        return remoteControlMode
    }

    fun testGetPopupLine(): Int {
        return mPopupLine
    }

    fun testGetPopupIcon(): Int {
        return mPopupIcon
    }

    fun testGetExposureLine(): Int {
        return mExposureLine
    }

    companion object {
        private const val TAG = "MainUI"

        private const val cachePopup = true // if false, we recreate the popup each time
        private const val viewRotateAnimationDuration =
            100 // duration in ms of the icon rotation animation
        const val privacyIndicatorGapDp: Int = 24

        private const val manualIsoValue = "m"

        /** Returns whether the ISO button with the supplied text is a match for the supplied iso.
         * Should only be used for Preview.supportsISORange()==true (i.e., full manual ISO).
         */
        fun ISOTextEquals(buttonText: String, iso: String): Boolean {
            // Can't use equals(), due to the \n that Popupview.getButtonOptionString() inserts, and
            // also good to make this general in case in future we support other text formats.
            // We really want to check that iso is the last word in buttonText.
            if (buttonText.endsWith(iso)) {
                return buttonText.length == iso.length || Character.isWhitespace(
                    buttonText[buttonText.length - iso.length - 1]
                )
            }
            return false
        }

        /** Returns the ISO button text for the supplied iso.
         * Should only be used for Preview.supportsISORange()==true (i.e., full manual ISO).
         */
        fun ISOToButtonText(iso: Int): String {
            // n.b., if we change how the ISO is converted to a string for the button, will also need
            // to update updateSelectedISOButton()
            return iso.toString()
        }
    }
}
