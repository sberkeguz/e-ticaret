package com.sarikaya.e_ticaret.catalog.controller.admin;

import com.sarikaya.e_ticaret.catalog.dto.CategoryRequest;
import com.sarikaya.e_ticaret.catalog.dto.CategoryResponse;
import com.sarikaya.e_ticaret.catalog.entity.Category;
import com.sarikaya.e_ticaret.catalog.repository.CategoryRepository;
import com.sarikaya.e_ticaret.common.exception.BadRequestException;
import com.sarikaya.e_ticaret.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryRepository categoryRepository;

    // Listelem get
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getAllCategories() {
        // Entity'leri doğrudan döndürmüyoruz; DTO'ya çeviriyoruz ki frontend'e
        // sadece id, name ve parentId gitsin (parent/children iç içe JSON'u oluşmasın).
        List<CategoryResponse> categories = categoryRepository.findAll()
                .stream()
                .map(CategoryResponse::from)
                .toList();

        return ResponseEntity.ok(categories);
    }

    // Ekleme: POST /api/admin/categories
    @PostMapping
    // @RequestBody: Frontend'den gelen { name, parentId } JSON'unu CategoryRequest'e çevirir.
    public ResponseEntity<CategoryResponse> createCategory(
            @RequestBody CategoryRequest request) {

        // Yeni kategori nesnesi oluşturup adını yazıyoruz.
        Category category = new Category();
        category.setName(request.name());
        // parentId doluysa üst kategoriyi bulup bağlıyoruz; boşsa kök kategori olur.
        category.setParents(findParents(request.parentIds(), null));

        // Veritabanına kaydedip, kaydedilmiş hâlini (artık id'si var) DTO olarak döndürüyoruz.
        Category saved = categoryRepository.save(category);
        return ResponseEntity.ok(CategoryResponse.from(saved));
    }

    // Güncelleme: PUT /api/admin/categories/{id}
    @PutMapping("/{id}")
    // @PathVariable: URL'deki {id} değerini alır.
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable Long id,
            @RequestBody CategoryRequest request) {

        // Güncellenecek kategoriyi bul; yoksa 404 hatası fırlat.
        Category category = categoryRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Kategori bulunamadı (id: " + id + ")"));

        // Yeni adı yaz.
        category.setName(request.name());
        // Üst kategoriyi yeniden belirle (null gelirse üst kategori kaldırılır).
        category.setParents(findParents(request.parentIds(), id));

        Category saved = categoryRepository.save(category);
        return ResponseEntity.ok(CategoryResponse.from(saved));
    }

    // Silme: DELETE /api/admin/categories/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        // Silmeden önce kategori var mı kontrol et; yoksa 404.
        if (!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException(
                    "Kategori bulunamadı (id: " + id + ")");
        }

        categoryRepository.deleteById(id);
        // Gövdesiz 200 OK döndür.
        return ResponseEntity.ok().build();
    }

    // İstekten gelen üst kategori ID'lerini doğrular ve entity nesnelerine dönüştürür.
    private Set<Category> findParents(List<Long> parentIds, Long categoryId) {
        // Üst kategori seçilmemişse boş küme döndürülür; kategori kök olur.
        if (parentIds == null || parentIds.isEmpty()) {
            return new LinkedHashSet<>();
        }

        // Set, aynı üst kategori ID'si birden fazla gönderilirse tekrarları kaldırır.
        Set<Long> requestedIds = new LinkedHashSet<>(parentIds);

        // Boş/null bir ID'yi veritabanında aramak yerine isteği 400 ile reddet.
        if (requestedIds.contains(null)) {
            throw new BadRequestException("Üst kategori ID'si boş olamaz.");
        }

        // Bir kategorinin kendisini kendi üst kategorisi olarak seçmesini engelle.
        if (categoryId != null && requestedIds.contains(categoryId)) {
            throw new BadRequestException("Kategori kendisini üst kategori seçemez.");
        }

        // Gelen ID'leri tek sorgu ile entity'lere çevir.
        List<Category> parents = categoryRepository.findAllById(requestedIds);

        // İstenen ID'lerden biri veritabanında bulunamazsa 404 hatası ver.
        if (parents.size() != requestedIds.size()) {
            throw new ResourceNotFoundException("Üst kategorilerden biri bulunamadı.");
        }

        // Güncellemede kategorinin kendi alt ağacını üst kategori seçerek döngü oluşturmasını engelle.
        if (categoryId != null) {
            ensureNoCycle(categoryId, requestedIds);
        }

        return new LinkedHashSet<>(parents);
    }

    // Yeni üst kategorilerin mevcut kategori ağacında döngü oluşturup oluşturmadığını kontrol eder.
    private void ensureNoCycle(Long categoryId, Set<Long> requestedParentIds) {
        for (Long parentId : requestedParentIds) {
            // Seçilen üst kategori, güncellenen kategorinin alt ağacındaysa ilişki döngü yaratır.
            if (isDescendant(categoryId, parentId, new HashSet<>())) {
                throw new BadRequestException(
                        "Bir alt kategori, kendi kategorisinin üst kategorisi olamaz.");
            }
        }
    }

    // targetId'nin ancestorId kategorisinin altındaki herhangi bir seviyede olup olmadığını bulur.
    private boolean isDescendant(
            Long ancestorId,
            Long targetId,
            Set<Long> visitedIds) {

        // Daha önce ziyaret edilen kategoriyi yeniden dolaşma; olası mevcut döngülerde sonsuzluğu önler.
        if (!visitedIds.add(ancestorId)) {
            return false;
        }

        // ancestorId'yi üst kategori olarak kullanan doğrudan alt kategorileri sırayla kontrol et.
        for (Category child : categoryRepository.findDistinctByParents_Id(ancestorId)) {
            // Hedef doğrudan alt kategoriyse veya alt ağacın daha aşağısındaysa bulundu.
            if (child.getId().equals(targetId)
                    || isDescendant(child.getId(), targetId, visitedIds)) {
                return true;
            }
        }

        return false;
    }}