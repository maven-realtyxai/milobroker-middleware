package com.milobroker.atlas.repository;
import com.milobroker.atlas.domain.Country;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
/** Read access to the countries catalog. */
public interface CountryRepository extends JpaRepository<Country, Long> {
  List<Country> findAllByOrderByCountryNameAsc();
  Optional<Country> findByShortForm(String shortForm);
}
