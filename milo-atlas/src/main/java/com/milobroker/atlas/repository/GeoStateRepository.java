package com.milobroker.atlas.repository;
import com.milobroker.atlas.domain.GeoState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
/** Read access to states scoped to their country. */
public interface GeoStateRepository extends JpaRepository<GeoState, Long> {
  List<GeoState> findByCountry_ShortFormOrderByStateNameAsc(String shortForm);
}
