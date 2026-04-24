package com.example.gateway.filter;

import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class CachingFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;

    public CachingFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!exchange.getRequest().getMethod().equals(HttpMethod.GET)) {
            return chain.filter(exchange);
        }

        String key = exchange.getRequest().getURI().toString();

        return redisTemplate.opsForValue().get(key)
            .flatMap(cached -> {
                System.out.println("Cache HIT: " + key);
                var response = exchange.getResponse();
                response.setStatusCode(HttpStatus.OK);
                response.getHeaders().add("Content-Type", "application/json");
                response.getHeaders().add("X-Cache", "HIT");
                DataBuffer buf = response.bufferFactory()
                    .wrap(cached.getBytes(StandardCharsets.UTF_8));
                return response.writeWith(Mono.just(buf));
            })
            .switchIfEmpty(Mono.defer(() -> {
                System.out.println("Cache MISS: " + key);
                DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();

                ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(exchange.getResponse()) {
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        Flux<? extends DataBuffer> flux = Flux.from(body);
                        return super.writeWith(flux.collectList().flatMapMany(dataBuffers -> {
                            DataBuffer joined = bufferFactory.join(dataBuffers);
                            byte[] bytes = new byte[joined.readableByteCount()];
                            joined.read(bytes);
                            DataBufferUtils.release(joined);
                            String bodyStr = new String(bytes, StandardCharsets.UTF_8);

                            if (getStatusCode() != null && getStatusCode().is2xxSuccessful()) {
                                redisTemplate.opsForValue()
                                    .set(key, bodyStr, Duration.ofSeconds(60))
                                    .subscribe();
                            }

                            return Flux.just(bufferFactory.wrap(bytes));
                        }));
                    }
                };

                return chain.filter(exchange.mutate().response(decorator).build());
            }));
    }

    @Override
    public int getOrder() { return -1; }
}
