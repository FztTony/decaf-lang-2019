package decaf.frontend.scope;

import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.LambdaSymbol;

import java.util.Optional;

/**
 * Formal scope: stores parameter variable symbols. It is owned by a Lambda symbol.
 */
public class LambdaScope extends Scope {

    public LambdaScope(Scope parent) {
        super(Kind.LAMBDA);
        ((LocalScope) parent).nestedLocalScopes().add(this);
    }

    public LambdaSymbol getOwner() {
        return owner;
    }

    public void setOwner(LambdaSymbol owner) {
        this.owner = owner;
    }

    @Override
    public boolean isLambdaScope() {
        return true;
    }

    public boolean nestedNotEmptyed() {
        return nested.isPresent();
    }
    /**
     * Get the local scope associated with the Lambda body.
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

    private LambdaSymbol owner;

    private Optional<LocalScope> nested;
}
