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
/** State / union-territory reference row (V1). Read-only for the APIs. */
@Entity @Table(name = "states")
public class GeoState {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "country_id", nullable = false)
  private Country country;
  @Column(name = "state_name", nullable = false)
  private String stateName;
  @Column(name = "type", nullable = false, length = 16)
  private String type;
  @Column(name = "state_code", nullable = false, length = 8)
  private String stateCode;
  protected GeoState() {}
  public Long getId() { return id; }
  public Country getCountry() { return country; }
  public String getStateName() { return stateName; }
  public String getType() { return type; }
  public String getStateCode() { return stateCode; }
}
