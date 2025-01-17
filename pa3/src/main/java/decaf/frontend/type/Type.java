package decaf.frontend.type;

import java.util.ArrayList;

/**
 * Type.
 * <p>
 * Decaf has a very simple type system, consisting of:
 * <ol>
 *     <li>basic types: int, bool, string (and void)</li>
 *     <li>array types</li>
 *     <li>class types</li>
 *     <li>function types (cannot be expressed in programs, but we use them to type check function calls)</li>
 * </ol>
 * <p>
 * Types are resolved by {@link decaf.frontend.typecheck.Typer}.
 *
 * @see BuiltInType
 * @see ClassType
 * @see ArrayType
 * @see FunType
 */
public abstract class Type {

    /**
     * Is this type int, bool, or string?
     *
     * @see BuiltInType#isBaseType
     */
    public boolean isBaseType() {
        return false;
    }

    public boolean isArrayType() {
        return false;
    }

    public boolean isClassType() {
        return false;
    }

    public boolean isFuncType() {
        return false;
    }

    public boolean isVoidType() {
        return false;
    }

    public boolean isConflict() {
        return false;
    }

    public boolean noError() {
        return true;
    }

    public boolean hasError() {
        return !noError();
    }

    /**
     * Tell if this type <em>is subtype of</em> another type.
     * <p>
     * Let {@code t1} {@literal <:} {@code t2} denote that type {@code t1} is subtype of {@code t2}. Rules:
     * <ol>
     *     <li>reflexive: {@code t} {@literal <:} {@code t}</li>
     *     <li>transitive: {@code t1} {@literal <:} {@code t3} if
     *          {@code t1} {@literal <:} {@code t2} and {@code t2} {@literal <:} {@code t3}</li>
     *     <li>error is special: {@code error} {@literal <:} {@code t}, {@code t} {@literal <:} {@code error}</li>
     *     <li>null is an object: {@code null} {@literal <:} {@code class c} for every class {@code c}</li>
     *     <li>class inheritance: {@code class c1} {@literal <:} {@code class c2} if {@code c1} extends {@code c2}</li>
     *     <li>function: {@code (t1, t2, ..., tn) => t} {@literal <:} {@code (s1, s2, ..., sn) => s} if
     *          {@code t} {@literal <:} {@code s} and {@code si} {@literal <:} {@code ti} for every {@code i}</li>
     * </ol>
     *
     * @param that another type
     * @return subtype checking result
     */
    public abstract boolean subtypeOf(Type that);

    /**
     * Tell if two types are equivalent.
     *
     * @param that another type
     * @return type equivalent checking result
     */
    public abstract boolean eq(Type that);

    public Type upper(Type that) {
        if (this.hasError())
            return that;
        if (that.hasError())
            return this;
        if (this.subtypeOf(that))
            return that;
        if (that.subtypeOf(this))
            return this;
        if (this.isClassType() && that.isClassType()) {
            ClassType th = (ClassType) that;
            while (th.superType.isPresent()) {
                th = th.superType.get();
                if (this.subtypeOf(th))
                    return th;
            }
        }
        if (this.isFuncType() && that.isFuncType()) {
            assert this instanceof FunType;
            assert that instanceof FunType;
            FunType f1 = (FunType) this, f2 = (FunType) that;
            int size = f1.argTypes.size();
            if (size != f2.argTypes.size())
                return BuiltInType.CONF;
            Type retType = f1.returnType.upper(f2.returnType);
            if (retType.eq(BuiltInType.CONF))
                return BuiltInType.CONF;
            var argType = new ArrayList<Type>();
            for (int i = 0; i < size; ++i) {
                argType.add(f1.argTypes.get(i).lower(f2.argTypes.get(i)));
                if (argType.get(i).eq(BuiltInType.CONF))
                    return BuiltInType.CONF;
            }
            return new FunType(retType, argType);
        }
        return BuiltInType.CONF;
    }

    public Type lower(Type that) {
        if (this.hasError())
            return that;
        if (that.hasError())
            return this;
        if (this.subtypeOf(that))
            return this;
        if (that.subtypeOf(this))
            return that;
        if (this.isFuncType() && that.isFuncType()) {
            assert this instanceof FunType;
            assert that instanceof FunType;
            FunType f1 = (FunType) this, f2 = (FunType) that;
            int size = f1.argTypes.size();
            if (size != f2.argTypes.size())
                return BuiltInType.CONF;
            Type retType = f1.returnType.lower(f2.returnType);
            if (retType.eq(BuiltInType.CONF))
                return BuiltInType.CONF;
            var argType = new ArrayList<Type>();
            for (int i = 0; i < size; ++i) {
                argType.add(f1.argTypes.get(i).upper(f2.argTypes.get(i)));
                if (argType.get(i).eq(BuiltInType.CONF))
                    return BuiltInType.CONF;
            }
            return new FunType(retType, argType);
        }
        return BuiltInType.CONF;
    }

    public abstract String toString();
}
