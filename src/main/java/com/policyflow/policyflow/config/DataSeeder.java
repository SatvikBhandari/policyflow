package com.policyflow.policyflow.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;

@Component
@Profile("!prod")
public class DataSeeder implements CommandLineRunner {

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;

    public DataSeeder(DepartmentRepository departmentRepository, UserRepository userRepository) {
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (departmentRepository.count() > 0 || userRepository.count() > 0) {
            return; // data already present, never overwrite
        }

        Department engineering = departmentRepository.save(new Department("Engineering", "ENG"));
        Department finance = departmentRepository.save(new Department("Finance", "FIN"));
        departmentRepository.save(new Department("Human Resources", "HR"));

        userRepository.save(new User("Aarav Sharma", "aarav@policyflow.local", Role.EMPLOYEE, engineering));
        userRepository.save(new User("Meera Iyer", "meera@policyflow.local", Role.MANAGER, engineering));
        userRepository.save(new User("Rohan Gupta", "rohan@policyflow.local", Role.FINANCE_OFFICER, finance));
        userRepository.save(new User("Kavita Rao", "kavita@policyflow.local", Role.DEPARTMENT_HEAD, engineering));
        userRepository.save(new User("Admin User", "admin@policyflow.local", Role.ADMIN, engineering));
    }
}