package store.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import store.product.ProductController;
import store.product.ProductOut;

@Slf4j
@Service
public class OrderService {

    // moeda em que o banco guarda os valores
    public static final String USD = "USD";

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductController productController;   // cliente Feign do contrato store:product

    // Sem @Transactional de proposito: as chamadas ao Product (HTTP) acontecem antes,
    // sem segurar conexao do banco; o save() abre a propria transacao e grava pedido + itens.
    public Order create(String idAccount, Order order) {
        requireAccount(idAccount);
        validate(order);   // tudo validado antes de chamar o Product

        List<OrderItem> items = order.items().stream()
            .map(this::price)
            .toList();

        BigDecimal total = items.stream()
            .map(OrderItem::total)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order toSave = Order.builder()
            .idAccount(idAccount)
            // sem fracao de segundo: sai como "2025-09-01T12:30:00", igual ao enunciado
            .date(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
            .items(items)
            .total(total)
            .build();

        return orderRepository.save(new OrderModel(toSave)).to().currency(USD);
    }

    public List<Order> findAll(String idAccount) {
        requireAccount(idAccount);
        return orderRepository.findByIdAccountOrderByDateDesc(idAccount).stream()
            .map(OrderModel::toSummary)
            .toList();
    }

    // pedido de outro usuario: 404 (o repositorio filtra pelo dono)
    public Order findById(String idAccount, String id, String currency) {
        requireAccount(idAccount);
        Order order = orderRepository.findByIdAndIdAccount(id, idAccount)
            .map(OrderModel::to)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        return convert(order, currency);
    }

    // PLACEHOLDER (Etapa 4): ainda sem Exchange, so USD e suportado; qualquer outra moeda -> 422
    private Order convert(Order order, String currency) {
        if (currency == null || currency.isBlank() || USD.equalsIgnoreCase(currency.trim())) {
            return order.currency(USD);
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Currency not supported: " + currency);
    }

    // id-account vem do Gateway (depois de validar o JWT); sem ele = usuario nao logado
    private void requireAccount(String idAccount) {
        if (idAccount == null || idAccount.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing id-account");
        }
    }

    private void validate(Order order) {
        if (order == null || order.items() == null || order.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Items are required");
        }
        for (OrderItem item : order.items()) {
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is required");
            }
            if (item.idProduct() == null || item.idProduct().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idProduct is required");
            }
            if (item.quantity() == null || item.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be greater than zero");
            }
        }
    }

    // busca o produto no Product e congela o preco atual no item
    private OrderItem price(OrderItem item) {
        ProductOut product = findProduct(item.idProduct());
        return OrderItem.builder()
            .idProduct(product.id())
            .quantity(item.quantity())
            .unitPrice(product.price())
            .total(product.price().multiply(BigDecimal.valueOf(item.quantity())))
            .build();
    }

    // produto inexistente -> 400 (regra do enunciado); Product fora do ar -> 503
    private ProductOut findProduct(String idProduct) {
        ProductOut product;
        try {
            product = productController.findById(idProduct).getBody();
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found: " + idProduct);
        } catch (FeignException e) {
            throw translate(e);
        }
        // corpo vazio (ex.: idProduct "health-check" casa GET /products/health-check, que responde 200 sem body)
        if (product == null || product.id() == null || product.price() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found: " + idProduct);
        }
        return product;
    }

    // mesmo criterio do AuthService: conexao recusada/timeout (status -1) ou 5xx -> 503.
    // Qualquer outro status inesperado vira 500 no ExceptionHandler.
    private RuntimeException translate(FeignException e) {
        if (e.status() < 0 || e.status() >= 500) {
            log.error("Product service unavailable: {}", e.getMessage());
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Product service unavailable");
        }
        return e;
    }

}
