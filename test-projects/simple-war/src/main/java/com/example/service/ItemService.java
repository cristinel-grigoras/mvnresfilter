package com.example.service;

import com.example.model.Item;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;

@ApplicationScoped
public class ItemService {

    @PersistenceContext(unitName = "simpleapp")
    private EntityManager em;

    public List<Item> findAll() {
        return em.createNamedQuery("Item.findAll", Item.class).getResultList();
    }

    public Item findById(Long id) {
        return em.find(Item.class, id);
    }

    public Item create(String name, String description) {
        Item item = new Item(name, description);
        em.persist(item);
        return item;
    }
}
