package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrganizerRepository extends JpaRepository<Organizer, Long> {
    boolean existsByUser(User user);

    Optional<Organizer> findByUser(User user);
}
