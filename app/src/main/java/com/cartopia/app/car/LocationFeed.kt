package com.cartopia.app.car

import com.cartopia.app.geo.GeoPoint

/**
 * A source of vehicle positions for one drive: [PleosLocationFeed] in the car, [DemoRouteFeed] for the demo drive.
 * [start] delivers fixes in time order until [stop]; a feed with a natural end (a replayed route) calls `onEnd`.
 */
interface LocationFeed {
    fun start(onFix: (GeoPoint) -> Unit, onEnd: () -> Unit = {})
    fun stop()
}
