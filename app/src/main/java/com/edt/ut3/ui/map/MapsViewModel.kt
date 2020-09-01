package com.edt.ut3.ui.map

import androidx.lifecycle.ViewModel
import com.edt.ut3.backend.requests.MapsServices
import com.edt.ut3.misc.forEach
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class MapsViewModel: ViewModel() {
    val crousPlaces = mutableMapOf<String, MutableList<SearchPlaceAdapter.Place>>()
    val paulSabatierPlaces = mutableMapOf<String, MutableList<SearchPlaceAdapter.Place>>()


    @Throws(IOException::class, JSONException::class)
    @Synchronized
    suspend fun getCrousPlaces() : MutableMap<String, MutableList<SearchPlaceAdapter.Place>> {
        return withContext(Default) {
            if (crousPlaces.isEmpty()) {
                val result = withContext(IO) {
                    val body = MapsServices().getCrousPlaces().body()?.string() ?: throw IOException()
                    JSONObject(body).getJSONArray("records")
                }

                withContext(Default) {
                    result.forEach { entry ->
                        val place = SearchPlaceAdapter.Place.fromJSON(entry as JSONObject)
                        crousPlaces.getOrPut(place.type) { mutableListOf() }.add(place)
                    }
                }
            }

            crousPlaces
        }
    }

    @Throws(IOException::class, JSONException::class)
    suspend fun getPaulSabatierPlaces() : MutableMap<String, MutableList<SearchPlaceAdapter.Place>> {
        return withContext(Default) {
            if (paulSabatierPlaces.isEmpty()) {
                val result = withContext(IO) {
                    val body = MapsServices().getPaulSabatierPlaces().body()?.string() ?: throw IOException()
                    JSONObject(body).getJSONArray("records")
                }

                withContext(Default) {
                    result.forEach { entry ->
                        val place = SearchPlaceAdapter.Place.fromJSON(entry as JSONObject)
                        paulSabatierPlaces.getOrPut(place.type) { mutableListOf() }.add(place)
                    }
                }
            }

            paulSabatierPlaces
        }
    }
}