package com.milobroker.atlas.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
/** Country reference row (V1). Read-only for the APIs. */
@Entity @Table(name = "countries")
public class Country {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "country_name", nullable = false)
  private String countryName;
  @Column(name = "country_short_form", nullable = false, length = 2)
  private String shortForm;
  @Column(name = "whatsapp_number", nullable = false)
  private String whatsappNumber;
  protected Country() {}
  public Long getId() { return id; }
  public String getCountryName() { return countryName; }
  public String getShortForm() { return shortForm; }
  public String getWhatsappNumber() { return whatsappNumber; }
}
