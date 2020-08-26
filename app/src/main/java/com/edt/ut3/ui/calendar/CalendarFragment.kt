package com.edt.ut3.ui.calendar

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.edt.ut3.R
import com.edt.ut3.backend.background_services.Updater
import com.edt.ut3.backend.background_services.Updater.Companion.Progress
import com.edt.ut3.backend.celcat.Event
import com.edt.ut3.misc.plus
import com.edt.ut3.misc.set
import com.edt.ut3.misc.timeCleaned
import com.elzozor.yoda.events.EventWrapper
import com.google.android.material.appbar.AppBarLayout
import kotlinx.android.synthetic.main.fragment_calendar.*
import kotlinx.android.synthetic.main.fragment_calendar.view.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.ExperimentalTime
import kotlin.time.days

class CalendarFragment : Fragment() {

    private val calendarViewModel by viewModels<CalendarViewModel> { defaultViewModelProviderFactory }

    private var job : Job? = null

    private var shouldBlockScroll = false
    private var canBlockScroll = false

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_calendar, container, false)
    }

    @ExperimentalTime
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()

        val worker = PeriodicWorkRequestBuilder<Updater>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(requireContext()).let {
            it.enqueueUniquePeriodicWork("event_update", ExistingPeriodicWorkPolicy.REPLACE, worker)
            it.getWorkInfoByIdLiveData(worker.id).observe(viewLifecycleOwner, Observer { workInfo ->
                workInfo?.let {
                    val progress = workInfo.progress
                    val value = progress.getInt(Progress, 0)

                    Log.i("PROGRESS", value.toString())
                }
            })
        }

        calendarView.setDate(calendarViewModel.selectedDate.time, false, false)
        calendarViewModel.getEvents(requireContext()).value?.let {
            handleEventsChange(requireView(), it)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @ExperimentalTime
    private fun setupListeners() {
        calendarViewModel.getEvents(requireContext()).observe(viewLifecycleOwner, Observer { evLi ->
            println("Event database has been updated")
            handleEventsChange(requireView(), evLi)
        })

        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            calendarViewModel.getEvents(requireContext()).value?.let {
                calendarViewModel.selectedDate = Date().set(year, month, dayOfMonth).timeCleaned()
                handleEventsChange(requireView(), it)
            }
        }

        app_bar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
            println("$verticalOffset ${appBarLayout.totalScrollRange} ${abs(verticalOffset) == appBarLayout.totalScrollRange}")

            shouldBlockScroll = canBlockScroll && (verticalOffset + appBarLayout.totalScrollRange == 0)
            canBlockScroll = (verticalOffset + appBarLayout.totalScrollRange > 0)
        })

        day_scroll.setOnScrollChangeListener { _: NestedScrollView?, _, scrollY, _, oldScrollY ->
            println("oldScrollY: $oldScrollY scrollY: $scrollY")
            when {
                shouldBlockScroll -> {
                    day_scroll.scrollTo(0,0)
                    shouldBlockScroll = false
                }
            }
        }
    }

    @ExperimentalTime
    private fun handleEventsChange(root: View, eventList: List<Event>?) {
        if (eventList == null) return

        root.day_view.setViewBuilder(this::buildEventView)
        filterEvents(root, eventList)
    }

    private fun buildEventView(context: Context, eventWrapper: EventWrapper, x: Int, y: Int, w:Int, h: Int)
            : Pair<Boolean, View> {
        return Pair(false, EventView(context, eventWrapper as Event.Wrapper).apply {
            setOnClickListener {
//                PopupMenu(context, this).apply {
//                    inflate(R.menu.event_menu)
//                    setOnMenuItemClickListener {
//                        when (it.itemId) {
//                            R.id.add_note -> {
//                                BottomSheetBehavior.from<CardView>(requireView().bottomNav).setState(STATE_EXPANDED)/*.state = STATE_EXPANDED*/
//                            }
//                            else -> println("wtf")
//                        }
//
//                        false
//                    }
//                }.show()
            }
        })
    }

    @ExperimentalTime
    private fun filterEvents(root: View, eventList: List<Event>) {
        val selectedDate = calendarViewModel.selectedDate

        job?.cancel()
        job = lifecycleScope.launch {
            println(selectedDate.toString())
            withContext(IO) {
                val events = withContext(Default) {
                    eventList.filter {
                            ev -> ev.start >= selectedDate
                            && ev.start <= selectedDate + 1.days
                    }.map { ev -> Event.Wrapper(ev) }
                }

                root.day_view.autoFitHours = true
                root.day_view.setEvents(events)

                withContext(Main) {
                    root.day_view.requestLayout()
                }
            }
        }
    }
}