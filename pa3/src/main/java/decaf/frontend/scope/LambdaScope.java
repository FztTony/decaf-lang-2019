package decaf.frontend.scope;

import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.VarSymbol;

import java.util.*;

/**
 * Formal scope: stores parameter variable symbols. It is owned by a Lambda symbol.
 */
public class LambdaScope extends Scope {

    public LambdaScope(Scope parent) {
        super(Kind.LAMBDA);
        ((LocalScope) parent).nestedLocalScopes().add(this);
        this.parent = parent;
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

    private Map<String, VarSymbol> capVar = new TreeMap<>();
    public final Scope parent;
    public List<VarSymbol> capVars() {
        var list = new ArrayList<>(capVar.values());
        Collections.sort(list);
        return list;
    }
    public void capture(VarSymbol varSymbol) {
        capVar.put(varSymbol.name, varSymbol);
    }
}
