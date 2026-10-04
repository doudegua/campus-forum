package net.doudegua.entity.vo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.function.Consumer;

public interface BaseData {

    default <V> V asViewObject(Class<V> clazz, Consumer<V> consumer) {
        V v = this.asViewObject(clazz);
        consumer.accept(v);
        return v;
    }

    default <V> V asViewObject(Class<V> clazz) {
        try{
            Field[] declaredFields = clazz.getDeclaredFields();
            Constructor<V> constructor = clazz.getDeclaredConstructor();
            V v  = constructor.newInstance();
            for (Field field : declaredFields) {
                convert(field, v);
            }
            return v;
        }catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception.getMessage());
        }
    }

    private void convert(Field field, Object vo) {
        try {
            Field source = this.getClass().getDeclaredField(field.getName());
            field.setAccessible(true);
            source.setAccessible(true);
            field.set(vo, source.get(this));
        } catch (NoSuchFieldException | IllegalAccessException ignored) {}
    }
}
