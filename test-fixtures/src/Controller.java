package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/items")
public class Controller {
    private final Service service;

    public Controller(Service service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public String getItem(@PathVariable String id) {
        return service.findById(id);
    }

    @PostMapping
    public void createItem(@RequestBody String value) {
        service.save("new", value);
    }

    @DeleteMapping("/{id}")
    public void deleteItem(@PathVariable String id) {
        service.delete(id);
    }
}
