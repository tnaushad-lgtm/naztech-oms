package com.naztech.oms.repo;

import com.naztech.oms.entity.News;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsRepo extends JpaRepository<News, Long> {
    List<News> findTop50ByOrderByPublishedAtDesc();
}
