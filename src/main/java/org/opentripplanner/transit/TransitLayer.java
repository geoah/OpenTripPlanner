package org.opentripplanner.transit;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Service;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.opentripplanner.streets.StreetLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 *
 */
public class TransitLayer implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(TransitLayer.class);

    // Do we really need to store this? It does serve as a key into the GTFS MapDB.
    public List<String> stopIdForIndex = new ArrayList<>();

    public static final int TYPICAL_NUMBER_OF_STOPS_PER_TRIP = 30;

    public List<TripPattern> tripPatterns = new ArrayList<>();

    // Maybe we need a StopStore that has (streetVertexForStop, transfers, flags, etc.)
    public TIntList streetVertexForStop = new TIntArrayList();

    // Inverse map of streetVertexForStop, and reconstructed from that list.
    public transient TIntIntMap stopForStreetVertex;

    // For each stop, a packed list of transfers to other stops
    public List<TIntList> transfersForStop;

    public List<TIntList> patternsForStop;

    public List<Service> services = new ArrayList<>();

    /**
     * Seems kind of hackish to pass the street layer in.
     * Maybe there should not even be separate street and transit layer classes.
     * Should we have separate stop objects in the transit layer and stop vertices in the street layer?
     *
     * It should be possible to load multiple GTFS feeds into the same transit layer.
     * This might be achieved by loading more than one feed into a MapDB, but it's probably better to keep the
     * one MapDB == one feed equivalence. That way when individual GTFS feeds are updated, we only need to reload
     * one MapDB, there are no identifier collision problems within one MapDB, and we don't need to store which feed
     * each entity belongs to in the MapDB.
     *
     * So loading multiple feeds would be achieved by calling a function on TransitLayer multiple times
     * with multiple GtfsFeed objects. All maps/lists would need to be initialized with empty objects at construction,
     * and all methods would have to be designed to be called multiple times in successtion.
     */
    public void loadFromGtfs (GTFSFeed gtfs, StreetLayer streetLayer) {

        // Load stops.
        // ID is the GTFS string ID, stopIndex is the zero-based index, stopVertexIndex is the index in the street layer.
        TObjectIntMap<String> indexForStopId = new TObjectIntHashMap<>();
        for (Stop stop : gtfs.stops.values()) {
            int stopIndex = stopIdForIndex.size();
            indexForStopId.put(stop.stop_id, stopIndex);
            stopIdForIndex.add(stop.stop_id);
            if (streetLayer != null) {
                int streetVertexIndex = streetLayer.linkTransitStop(stop.stop_lat, stop.stop_lon, 300);
                streetVertexForStop.add(streetVertexIndex);
            }
        }

        // Load service periods, assigning integer codes which will be referenced by trips and patterns.
        TObjectIntMap<String> serviceCodeNumber = new TObjectIntHashMap<>(20, 0.5f, -1);
        gtfs.services.forEach((serviceId, service) -> {
            int serviceIndex = services.size();
            services.add(service);
            serviceCodeNumber.put(serviceId, serviceIndex);
            LOG.info("serviceID {} assigned numeric code {}", serviceId, serviceIndex);
        });

        // Group trips by stop pattern (including pickup/dropoff type) and fill stop times into patterns.
        // Also group trips by the blockId they belong to, and chain them together if they allow riders to stay on board
        // the vehicle from one trip to the next, even if it changes routes or directions. This is called "interlining".

        LOG.info("Grouping trips by stop pattern and block, and creating trip schedules.");
        // These are temporary maps used only for grouping purposes.
        Map<TripPatternKey, TripPattern> tripPatternForStopSequence = new HashMap<>();
        Multimap<String, TripSchedule> tripsForBlock = HashMultimap.create();
        int nTripsAdded = 0;
        for (String tripId : gtfs.trips.keySet()) {
            Trip trip = gtfs.trips.get(tripId);
            // Construct the stop pattern and schedule for this trip
            // Should we really be resolving to an object reference for Route?
            // That gets in the way of GFTS persistence.
            TripPatternKey tripPatternKey = new TripPatternKey(trip.route.route_id);
            TIntList arrivals = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);
            TIntList departures = new TIntArrayList(TYPICAL_NUMBER_OF_STOPS_PER_TRIP);
            for (StopTime st : gtfs.getOrderedStopTimesForTrip(tripId)) {
                tripPatternKey.addStopTime(st, indexForStopId);
                arrivals.add(st.arrival_time);
                departures.add(st.departure_time);
            }
            TripPattern tripPattern = tripPatternForStopSequence.get(tripPatternKey);
            if (tripPattern == null) {
                tripPattern = new TripPattern(tripPatternKey);
                tripPatternForStopSequence.put(tripPatternKey, tripPattern);
                tripPatterns.add(tripPattern);
            }
            tripPattern.setOrVerifyDirection(trip.direction_id);
            int serviceCode = serviceCodeNumber.get(trip.service.service_id);
            TripSchedule tripSchedule = new TripSchedule(trip, arrivals.toArray(), departures.toArray(), serviceCode);
            tripPattern.addTrip(tripSchedule);
            nTripsAdded += 1;
            // Record which block this trip belongs to, if any.
            if ( ! Strings.isNullOrEmpty(trip.block_id)) {
                tripsForBlock.put(trip.block_id, tripSchedule);
            }
        }
        LOG.info("Done creating {} trips on {} patterns.", nTripsAdded, tripPatternForStopSequence.size());

        LOG.info("Chaining trips together according to blocks to model interlining...");
        // Chain together trips served by the same vehicle that allow transfers by simply staying on board.
        // Elsewhere this is done by grouping by (serviceId, blockId) but this is not supported by the spec.
        // Discussion started on gtfs-changes.
        tripsForBlock.asMap().forEach((blockId, trips) -> {
            TripSchedule[] schedules = trips.toArray(new TripSchedule[trips.size()]);
            Arrays.sort(schedules); // Sorts on first departure time
            for (int i = 0; i < schedules.length - 1; i++) {
                schedules[i].chainTo(schedules[i + 1]);
            }
        });
        LOG.info("Done chaining trips together according to blocks.");

        // Will be useful in naming patterns.
//        LOG.info("Finding topology of each route/direction...");
//        Multimap<T2<String, Integer>, TripPattern> patternsForRouteDirection = HashMultimap.create();
//        tripPatterns.forEach(tp -> patternsForRouteDirection.put(new T2(tp.routeId, tp.directionId), tp));
//        for (T2<String, Integer> routeAndDirection : patternsForRouteDirection.keySet()) {
//            RouteTopology topology = new RouteTopology(routeAndDirection.first, routeAndDirection.second, patternsForRouteDirection.get(routeAndDirection));
//        }

    }


    /** (Re-)build transient indexes of this TripPattern, connecting stops to patterns etc. */
    public void rebuildTransientIndexes () {

        // 1. Which patterns pass through each stop?
        // We could store references to patterns rather than indexes.
        int nStops = stopIdForIndex.size();
        patternsForStop = new ArrayList<>(nStops);
        for (int i = 0; i < nStops; i++) {
            patternsForStop.add(new TIntArrayList());
        }
        int p = 0;
        for (TripPattern pattern : tripPatterns) {
            for (int stopIndex : pattern.stops) {
                if (!patternsForStop.get(stopIndex).contains(p)) {
                    patternsForStop.get(stopIndex).add(p);
                }
            }
            p++;
        }

        // 2. What street vertex represents each transit stop? Invert the serialized map.
        stopForStreetVertex = new TIntIntHashMap();
        for (int s = 0; s < streetVertexForStop.size(); s++) {
            stopForStreetVertex.put(streetVertexForStop.get(s), s);
        }
    }

    public static TransitLayer fromGtfs (String file, StreetLayer streetLayer) {
        GTFSFeed gtfs = GTFSFeed.fromFile(file);
        TransitLayer transitLayer = new TransitLayer();
        transitLayer.loadFromGtfs(gtfs, streetLayer);
        return transitLayer;
    }

    public int getStopCount () {
        return stopIdForIndex.size();
    }

}
