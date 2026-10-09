package com.sarikaya.e_ticaret.controller.admin;

import com.sarikaya.e_ticaret.entity.Brand;
import com.sarikaya.e_ticaret.exceptions.ResourceNotFoundException;
import com.sarikaya.e_ticaret.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/brands")
@RequiredArgsConstructor

public class AdminBrandController {

    private final BrandRepository brandRepository;
    //listele
    @GetMapping
    public ResponseEntity<List<Brand>>  getAllBrands(){

        return ResponseEntity.ok(brandRepository.findAll());
    }
//ekle
    @PostMapping
    public ResponseEntity<Brand> createBrand(@RequestBody Brand brand) {
        Brand savedBrand = brandRepository.save(brand);
        return ResponseEntity.ok(savedBrand);
    }
 //sil
 @DeleteMapping("/{id}")
 public ResponseEntity<Void> deleteBrand(@PathVariable Long id) {
     if (!brandRepository.existsById(id)) {
         throw new ResourceNotFoundException("Marka bulunamadı (id: " + id + ")");
     }
     brandRepository.deleteById(id);
     return ResponseEntity.ok().build();
 }


 //güncelleme
 @PutMapping("/{id}")
 public ResponseEntity<Brand> updateBrand(@PathVariable Long id, @RequestBody Brand brandDetails) {
     Brand brand = brandRepository.findById(id)
             .orElseThrow(() -> new ResourceNotFoundException("Marka bulunamadı (id: " + id + ")"));

     brand.setName(brandDetails.getName());
     return ResponseEntity.ok(brandRepository.save(brand));
 }

}
