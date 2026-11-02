package com.lockerline.parcel;

import org.springframework.stereotype.Service;

import com.lockerline.error.NoSuchParcel;

@Service
/** Reading one parcel, with its events. */
public class ParcelService {

    private final ParcelRepository parcels;
    private final EventLog events;

    public ParcelService(ParcelRepository parcels, EventLog events) {
        this.parcels = parcels;
        this.events = events;
    }

    public ParcelView parcel(String publicId) {
        long id = ParcelIds.parse(publicId)
                .orElseThrow(() -> new NoSuchParcel(publicId));

        ParcelRepository.Row row = parcels.find(id)
                .orElseThrow(() -> new NoSuchParcel(publicId));

        return new ParcelView(
                ParcelIds.format(row.id()),
                row.door(),
                row.sizeClass(),
                row.flat(),
                row.state(),
                row.droppedAt(),
                events.forParcel(row.id()));
    }
}