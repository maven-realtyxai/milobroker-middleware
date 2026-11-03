package com.milobroker.atlas.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
/** City reference row linked to its state (V1). Read-only for the APIs. */
@Entity @Table(name = "cities")
public class City {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "city_name", nullable = false)
  private String cityName;
  @Column(name = "city_code", nullable = false, unique = true)
  private String cityCode;
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "state_id", nullable = false)
  private GeoState state;
  protected City() {}
  public Long getId() { return id; }
  public String getCityName() { return cityName; }
  public String getCityCode() { return cityCode; }
  public GeoState getState() { return state; }
}
