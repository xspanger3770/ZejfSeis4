package com.morce.zejfseis4.ui.model;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.morce.zejfseis4.ui.renderer.TableCellRendererAdapter;

abstract class Column<E, T> {

    private final String name;
    private final Function<E, T> valueGetter;
    private final Class<T> columnType;
	private TableCellRendererAdapter<E, ?> renderer;

    private Column(String name, Class<T> columnClass, Function<E, T> valueGetter, TableCellRendererAdapter<E, ?> renderer) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.columnType = Objects.requireNonNull(columnClass, "column class cannot be null");
        this.valueGetter = Objects.requireNonNull(valueGetter, "value getter cannot be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer cannot be null");
    }	

    public static <E, T> Column<E, T> editable(String name, Class<T> columnClass, Function<E, T> valueGetter,
                                               BiConsumer<E, T> valueSetter, TableCellRendererAdapter<E, ?> renderer) {
        return new Editable<>(name, columnClass, valueGetter, valueSetter, renderer);
    }

    public static <E, T> Column<E, T> readonly(String name, Class<T> columnClass, Function<E, T> valueGetter, TableCellRendererAdapter<E, T> renderer) {
        return new ReadOnly<>(name, columnClass, valueGetter, renderer);
    }

    abstract boolean isEditable();

    abstract void setValue(Object value, E entity);

    T getValue(E entity) {
        return valueGetter.apply(entity);
    }

    String getName() {
        return name;
    }

    Class<T> getColumnType() {
        return columnType;
    }
    
    public TableCellRendererAdapter<E, ?> getRenderer() {
		return renderer;
	}

    private static class ReadOnly<E, T> extends Column<E, T> {

        private ReadOnly(String name, Class<T> columnClass, Function<E, T> valueGetter, TableCellRendererAdapter<E, ?> renderer) {
            super(name, columnClass, valueGetter, renderer);
        }

        @Override
        boolean isEditable() {
            return false;
        }

        @Override
        void setValue(Object value, E entity) {
            throw new UnsupportedOperationException("Column '" + getName() + "' is not editable");
        }
    }

    private static class Editable<E, T> extends Column<E, T> {

        private final BiConsumer<E, T> valueSetter;

        private Editable(String name, Class<T> columnClass, Function<E, T> valueGetter, BiConsumer<E, T> valueSetter, TableCellRendererAdapter<E, ?> renderer) {
            super(name, columnClass, valueGetter, renderer);
            this.valueSetter = Objects.requireNonNull(valueSetter, "value setter cannot be null");
        }

        @Override
        boolean isEditable() {
            return true;
        }

        @Override
        void setValue(Object value, E entity) {
            valueSetter.accept(entity, getColumnType().cast(value));
        }
    }
}

