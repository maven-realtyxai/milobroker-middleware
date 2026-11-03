package com.milobroker.atlas.api;

import com.milobroker.atlas.service.ContactService;
import com.milobroker.atlas.service.ContactService.ContactResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public contact lookup consumed by the MiloBroker promotion site. */
@RestController @RequestMapping("/api/contact")
public class ContactController {
  private final ContactService contacts;

  public ContactController(ContactService contacts) { this.contacts = contacts; }

  @GetMapping
  public ContactResponse contact(@RequestParam(defaultValue = "IN") String countryIso2) {
    return contacts.contact(countryIso2);
  }
}
