package com.example;

import java.util.List;

public class Service {
    private final Repository repository;

    public Service(Repository repository) {
        this.repository = repository;
    }

    public String findById(String id) {
        return repository.get(id);
    }

    public List<String> findAll() {
        return repository.getAll();
    }

    public void save(String id, String value) {
        repository.put(id, value);
    }

    public void delete(String id) {
        repository.remove(id);
    }
}
