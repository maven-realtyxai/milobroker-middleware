package com.milobroker.concierge.service;

/** Listing writes and counts behind the Marketplace API. Concierge never touches bizdb. */
public interface MarketplaceListingsClient {
  /** Creates a listing; returns the new listing id. */
  String createListing(String ownerUserId, String title, String description,
      String listingType, String propertyType, String cityCode, String localityCode,
      Integer bedrooms, Long priceInr);

  /** Number of ACTIVE listings owned by this user. */
  int activeCount(String ownerUserId);
}
