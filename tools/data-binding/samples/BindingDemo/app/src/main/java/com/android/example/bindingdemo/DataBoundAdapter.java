package com.android.example.bindingdemo;

import android.support.v7.widget.RecyclerView;
import android.view.ViewGroup;

import android.databinding.DataBindingUtil;
import android.databinding.ViewDataBinding;

abstract public class DataBoundAdapter<T extends ViewDataBinding>
        extends RecyclerView.Adapter<DataBoundAdapter.DataBoundViewHolder<T>> {
    final int mLayoutId;
    final Class<T> mBinderInterface;
    public DataBoundAdapter(int mLayoutId, Class<T> mBinderInterface) {
        this.mLayoutId = mLayoutId;
        this.mBinderInterface = mBinderInterface;
    }

    @Override
    public DataBoundAdapter.DataBoundViewHolder<T> onCreateViewHolder(ViewGroup viewGroup, int type) {
        T binder = DataBindingUtil.inflate(viewGroup.getContext(), mLayoutId, viewGroup, false);
        return new DataBoundViewHolder(binder);
    }

    static class DataBoundViewHolder<T extends ViewDataBinding> extends RecyclerView.ViewHolder {
        public final T dataBinder;
        public DataBoundViewHolder(T mViewBinder) {
            super(mViewBinder.getRoot());
            this.dataBinder = mViewBinder;
        }
    }
}
