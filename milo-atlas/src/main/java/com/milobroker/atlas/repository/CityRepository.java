package com.milobroker.atlas.repository;
import com.milobroker.atlas.domain.City;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
/** Read access to cities scoped to their state code. */
public interface CityRepository extends JpaRepository<City, Long> {
  List<City> findByState_StateCodeOrderByCityNameAsc(String stateCode, Pageable pageable);
}
