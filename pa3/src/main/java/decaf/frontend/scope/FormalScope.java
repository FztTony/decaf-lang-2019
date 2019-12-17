package decaf.frontend.scope;

import decaf.frontend.symbol.MethodSymbol;

import java.util.Optional;

/**
 * Formal scope: stores parameter variable symbols. It is owned by a method symbol.
 */
public class FormalScope extends Scope {

    public FormalScope() {
        super(Kind.FORMAL);
        nested = Optional.empty();
    }

    public MethodSymbol getOwner() {
        return owner;
    }

    public void setOwner(MethodSymbol owner) {
        this.owner = owner;
    }

    @Override
    public boolean isFormalScope() {
        return true;
    }

    public boolean nestedNotEmptyed() {
        return nested.isPresent();
    }
    /**
     * Get the local scope associated with the method body.
     *
     * @return local scope
     */
    public LocalScope nestedLocalScope() {
        return nested.get();
    }

    /**
     * Set the local scope.
     *
     * @param scope local scope
     */
    void setNested(LocalScope scope) {
        nested = Optional.of(scope);
    }

    private MethodSymbol owner;

    private Optional<LocalScope> nested;
}
