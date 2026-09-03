package com.aditya.rtos_doorbell.repository;

import com.aditya.rtos_doorbell.entity.Person;
import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface PersonRepository extends JpaRepository<Person, UUID> {
    Optional<Person> findByNameIgnoreCase(String name);

    @Query("select distinct p from Person p left join fetch p.embeddings order by p.createdAt")
    List<Person> findAllWithEmbeddings();
}
