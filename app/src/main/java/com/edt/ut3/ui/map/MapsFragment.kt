package com.edt.ut3.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.activity.addCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import com.edt.ut3.R
import com.edt.ut3.backend.maps.MapsUtils
import com.edt.ut3.backend.maps.Place
import com.edt.ut3.backend.preferences.PreferencesManager
import com.edt.ut3.misc.hideKeyboard
import com.edt.ut3.ui.map.custom_makers.LocationMarker
import com.edt.ut3.ui.map.custom_makers.PlaceMarker
import com.edt.ut3.ui.preferences.Theme
import com.google.android.material.bottomsheet.BottomSheetBehavior.*
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_maps.*
import kotlinx.android.synthetic.main.fragment_maps.view.*
import kotlinx.android.synthetic.main.place_info.view.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.util.*
import kotlin.collections.HashSet

class MapsFragment : Fragment() {

    enum class State { MAP, SEARCHING, PLACE }
    private var selectedPlace: Place? = null
    private var selectedPlaceMarker: PlaceMarker? = null
    private var state = MutableLiveData(State.MAP)

    private val viewModel: MapsViewModel by viewModels { defaultViewModelProviderFactory }

    private var searchJob : Job? = null
    private var downloadJob : Job? = null

    private val selectedCategories = HashSet<String>()
    private val places = mutableListOf<Place>()

    override fun onPause() {
        super.onPause()
        // Do not remove this line until we use osmdroid
        map.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Do not remove this line until we use omsdroid
        map.onResume()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_maps, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configureMap()
        setupListeners()
        moveToPaulSabatier()

        startDownloadJob()
    }

    /**
     * Configures the MapView
     * for a better user experience.
     *
     */
    private fun configureMap() {
        val path: File = requireContext().filesDir
        val osmdroidBasePathNew = File(path, "osmdroid")
        osmdroidBasePathNew.mkdirs()
        val osmdroidTileCacheNew = File(osmdroidBasePathNew, "tiles")
        osmdroidTileCacheNew.mkdirs()

        val configuration = Configuration.getInstance()
        configuration.apply {
            userAgentValue = requireActivity().packageName
//            osmdroidBasePath = osmdroidBasePathNew
//            osmdroidTileCache = osmdroidTileCacheNew
        }

        map.apply {
            // Which tile source will gives
            // us the map resources, otherwise
            // it cannot display the map.
            val tileSource = XYTileSource(
                "HOT", 1, 20, 256, ".png", arrayOf(
                    "http://a.tile.openstreetmap.fr/hot/",
                    "http://b.tile.openstreetmap.fr/hot/",
                    "http://c.tile.openstreetmap.fr/hot/"
                ), "© OpenStreetMap contributors"
            )

            setTileSource(tileSource)

            // This setting allows us to correctly see
            // the text on the screen ( adapt to the screen's dpi )
            isTilesScaledToDpi = true

            // Allows the user to zoom with its fingers.
            setMultiTouchControls(true)

            // Disable the awful zoom buttons as the
            // user can now zoom with its fingers
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)


            // As the MapView's setOnClickListener function did nothing
            // I decided to add an overlay to detect click and scroll events.
            val overlay = object: Overlay() {
                override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                    state.value = State.MAP
                    selectedPlaceMarker?.closeInfoWindow()

                    return super.onSingleTapConfirmed(e, mapView)
                }

                override fun onScroll(
                    pEvent1: MotionEvent?,
                    pEvent2: MotionEvent?,
                    pDistanceX: Float,
                    pDistanceY: Float,
                    pMapView: MapView?
                ): Boolean {
                    if (state.value == State.SEARCHING) {
                        state.value = State.MAP
                    }

                    return super.onScroll(pEvent1, pEvent2, pDistanceX, pDistanceY, pMapView)
                }
            }

            overlays.add(overlay)

            setupLocationListener()

            when (PreferencesManager.getInstance(requireContext()).currentTheme()) {
                Theme.LIGHT -> overlayManager.tilesOverlay.setColorFilter(null)
                Theme.DARK -> overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            }
        }
    }

    /**
     * This function will set the location listener
     * that will track the user's location and
     * display it on the map.
     *
     * The suppress lint is put there as
     * the requestLocationPermissionIfNecessary is
     * called and check it before executing it
     */
    @SuppressLint("MissingPermission")
    private fun setupLocationListener() {
        val listener = object: LocationListener {
            override fun onLocationChanged(p0: Location) {
                view?.let {
                    it.map.overlays.removeAll { it is LocationMarker }
                    it.map.overlays.add(LocationMarker(it.map).apply {
                        title = "position"
                        position = GeoPoint(p0)
                    })
                }
            }

            /**
             * Unused but necessary to avoid crash
             * due to function deprecation
             */
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            /**
             * Unused but necessary to avoid crash
             * due to function deprecation
             */
            override fun onProviderEnabled(provider: String) {}

            /**
             * Unused but necessary to avoid crash
             * due to function deprecation
             */
            override fun onProviderDisabled(provider: String) {}
        }

        val manager = requireActivity().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        executeIfLocationPermissionIsGranted {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                listener
            )
        }
    }

    /**
     * Setup all the listeners to handle user's actions.
     */
    private fun setupListeners() {
        //text, start, before, count
        search_bar.doOnTextChanged { text, _, _, _ ->
            handleTextChanged(text.toString())
        }

        search_bar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                refreshPlaces()
                handleTextChanged(search_bar.text.toString())
                state.value = State.SEARCHING
            }
        }
        search_bar.setOnClickListener {
            refreshPlaces()
            handleTextChanged(search_bar.text.toString())
            state.value = State.SEARCHING
        }

        state.observe(viewLifecycleOwner) { handleStateChange(it) }

        setupBackButtonPressCallback()

        my_location.setOnClickListener {
            val locationMarker = map.overlays.find { it is LocationMarker } as? LocationMarker
            locationMarker?.let { me ->
                smoothMoveTo(me.position)
            }
        }

        viewModel.getPlaces(requireContext()).observe(viewLifecycleOwner) { newPlaces ->
            setupCategoriesAndPlaces(newPlaces)
        }
    }

    private fun executeIfLocationPermissionIsGranted(callback: () -> Unit) {
        // We check if the permissions are granted
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // If not we request it
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                0
            )

            return
        }

        // Otherwise we execute the callback
        callback()
    }

    /**
     * Simply redirect to the filterResults function
     * that will filter the results.
     *
     * @param text The search bar text
     */
    private fun handleTextChanged(text: String) = filterResults(text)

    /**
     * Filter the results and assign a job to it.
     * When the function is called a second time
     * while the job isn't finished yet, the previous
     * one is canceled and replaced by the new one.
     *
     * @param text The search bar text
     */
    private fun filterResults(text: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launchWhenResumed {
            // We first set the text to lower case in order
            // to do a non-sensitive case search.
            val lowerCaseText = text.toLowerCase(Locale.getDefault())

            // We then filter the result and assign them to a variable
            val matchingPlaces = withContext(Default) {
                // Filtering the places to keep only the ones that matches
                // the selected categories.
                // If no category is selected the list is kept as it.
                val searchingList = if (selectedCategories.isNotEmpty()) {
                    places.filter { selectedCategories.contains(it.type) }
                } else { places }

                // After that, we keep only the ones that contains the
                // search bar text in their title and return them
                searchingList
                    .filter { it.title.toLowerCase(Locale.getDefault()).contains(lowerCaseText) }
                    .toTypedArray()
            }

            // We them add them to the ListView that will contains the search
            // result.
            // As it modify the view I prefer do it on the Main thread to avoid problems.
            withContext(Main) {
                search_result.adapter = SearchPlaceAdapter(requireContext(), matchingPlaces)
                search_result.setOnItemClickListener { adapterView, view, pos, id ->
                    if (pos != -1) {
                        val place = search_result.getItemAtPosition(pos) as Place
                        selectedPlace = place
                        state.value = State.PLACE
                    }
                }
            }
        }
    }

    /**
     * This function is in charge to handle
     * when the user press the back button.
     * The behavior depends on what's the current
     * state.
     * Given that state it will simply changes the
     * current state or pop the fragment stack.
     */
    private fun setupBackButtonPressCallback() {
        val activity = requireActivity()
        activity.onBackPressedDispatcher.addCallback(this) {
            when (state.value) {
                State.SEARCHING, State.PLACE -> state.value = State.MAP
                else -> {
                    isEnabled = false
                    activity.onBackPressed()
                }
            }
        }
    }

    private fun moveToPaulSabatier() {
        val paulSabatier = GeoPoint(43.5618994, 1.4678633)
        smoothMoveTo(paulSabatier, 15.0)
    }

    /**
     * This function is in charge to download
     * the Paul Sabatier places and the Crous places.
     * In any cases it displays a Snackbar which
     * indicates the result.
     */
    private fun startDownloadJob() {
        // If a download job is pending, we do not
        // launch an another job.
        if (downloadJob?.isActive == true) {
            return
        }

        // Assign the downloadJob to the new operation
        downloadJob = lifecycleScope.launchWhenResumed {
            withContext(Main) {
                Snackbar.make(maps_main, R.string.data_update, Snackbar.LENGTH_SHORT).show()
            }

            val downloadResult = viewModel.launchDataUpdate(requireContext())

            // This callback will hold the callback action.
            // We put it in a variable to avoid duplicate code
            // as the code must be use in a post {} function
            // of the main view (to avoid view nullability and things like that)
            val callback : () -> Unit
            when (downloadResult.errorCount) {
                // Display success message
                0 -> callback = {
                    Snackbar.make(maps_main, R.string.maps_update_success, Snackbar.LENGTH_LONG)
                        .show()
                }

                // Display an error message depending on
                // what type of error it is
                1 -> callback = {
                    val errRes = when (downloadResult.error) {
                        is JSONException -> R.string.building_data_invalid
                        else -> R.string.building_update_failed
                    }

                    Snackbar.make(maps_main, errRes, Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.action_retry) {
                            startDownloadJob()
                        }
                        .show()
                }

                // Display an error message depending on
                // what type of error it is
                2 -> callback = {
                    val errRes = when (downloadResult.error) {
                        is JSONException -> R.string.restaurant_data_invalid
                        else -> R.string.restaurant_update_failed
                    }

                    Snackbar.make(maps_main, errRes, Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.action_retry) {
                            startDownloadJob()
                        }
                        .show()
                }

                // Display an internet error message
                else -> callback = {
                    Snackbar.make(
                        maps_main,
                        R.string.unable_to_retrieve_data,
                        Snackbar.LENGTH_INDEFINITE
                    ).show()
                }
            }

            // Calling the callback.
            Log.d(this::class.simpleName, downloadResult.toString())
            maps_main?.post(callback)
        }
    }

    private fun setupCategoriesAndPlaces(incomingPlaces: List<Place>) {
        places.clear()
        places.addAll(incomingPlaces)
        filters_group.post {
            filters_group.run {
                val categories = places.map { it.type }.toHashSet()
                categories.forEach { category ->
                    addView(
                        Chip(requireContext()).apply {
                            setChipDrawable(
                                ChipDrawable.createFromAttributes(
                                    requireContext(),
                                    null,
                                    0,
                                    R.style.Widget_MaterialComponents_Chip_Filter
                                )
                            )

                            val bgColor = ContextCompat.getColor(context, R.color.foregroundColor)
                            val iconTint = ContextCompat.getColor(context, R.color.iconTint)
                            val states = arrayOf(
                                intArrayOf(-android.R.attr.state_enabled), // disabled
                                intArrayOf(-android.R.attr.state_enabled), // disabled
                                intArrayOf(-android.R.attr.state_checked), // unchecked
                                intArrayOf(-android.R.attr.state_pressed)  // unpressed
                            )
                            chipBackgroundColor = ColorStateList(states, (0..3).map { bgColor }.toIntArray())
                            checkedIconTint = ColorStateList(states, (0..3).map { iconTint }.toIntArray())
                            text = category
                            isClickable = true

                            setOnCheckedChangeListener { compoundButton: CompoundButton?, b: Boolean ->
                                val cate = text.toString()
                                if (b) {
                                    selectedCategories.add(cate)
                                    Log.d(this::class.simpleName, "Added: $cate")
                                } else {
                                    selectedCategories.remove(cate)
                                    Log.d(this::class.simpleName, "Removed: $cate")
                                }


                                refreshPlaces()
                                filterResults(requireView().search_bar.text.toString())
                            }

                            refreshPlaces()
                        }
                    )
                }
            }
        }
    }

    private fun refreshPlaces() {
        val placesToShow = if (selectedCategories.isNotEmpty()) {
            places.filter { it.type in selectedCategories }
        } else { places }


        map.overlays.forEach { if (it is Marker) { it.closeInfoWindow() } }
        map.overlays.removeAll { it is PlaceMarker }
        addPlacesOnMap(placesToShow)

        map.invalidate()
        map.requestLayout()
    }

    private fun addPlacesOnMap(places: List<Place>) {
        places.forEach { curr ->
            map.overlays.add(
                PlaceMarker(map, curr.copy()).apply {
                    onLongClickListener = {
                        selectedPlace = place
                        state.value = State.PLACE
                        true
                    }
                }
            )
        }
    }

    private fun handleStateChange(state: State) {
        when (state) {
            State.MAP -> {
                foldEverything()
                refreshPlaces()
                selectedPlaceMarker = null
            }

            State.SEARCHING -> unfoldSearchTools()

            State.PLACE -> displayPlaceInfo()
        }
    }

    private fun foldEverything() {
        filters_container.visibility = GONE
        search_result.visibility = GONE
        from(place_info_container).state = STATE_HIDDEN
        requireActivity().nav_view.visibility = VISIBLE

        search_bar.clearFocus()
        map.requestFocus()

        hideKeyboard()
    }

    private fun unfoldSearchTools() {
        search_result.visibility = VISIBLE
        filters_container.visibility = VISIBLE
        from(place_info_container).state = STATE_HIDDEN

        requireActivity().nav_view.visibility = GONE
    }

    private fun displayPlaceInfo() {
        selectedPlace?.let { selected ->
            search_result.visibility = GONE
            filters_container.visibility = GONE
            requireActivity().nav_view.visibility = GONE
            hideKeyboard()

            lifecycleScope.launchWhenStarted {
                delay(500)
                from(place_info_container).state = STATE_EXPANDED
            }

            place_info.titleText = selected.title
            place_info.descriptionText = selected.short_desc ?: getString(R.string.no_description_available)
            place_info.picture = selected.photo
            place_info.go_to.setOnClickListener {
                val me = map.overlays.find { it is LocationMarker } as LocationMarker?
                activity?.let {
                    MapsUtils.routeFromTo(it, me?.position, GeoPoint(selected.geolocalisation), selected.title) {
                        maps_main?.let {
                            Snackbar.make(it, R.string.unable_to_launch_googlemaps, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }

            map.overlays.removeAll { marker -> marker is PlaceMarker }
            map.overlays.add(
                PlaceMarker(map, selected).also { marker ->
                    marker.showInfoWindow()
                }.also {
                    selectedPlaceMarker = it
                }
            )


            smoothMoveTo(selected.geolocalisation)
        }
    }

    /**
     * Does a smooth move to the given
     * position.
     *
     * @param position The wanted position
     * @param zoom The zoom amount
     * @param ms The time in ms
     */
    private fun smoothMoveTo(position: GeoPoint, zoom: Double = 17.0, ms: Long = 1000L) {
        map.controller.animateTo(position, zoom, ms)
    }
}