package com.birbit.android.bindingdemo;

import android.support.v7.widget.RecyclerView;
import android.view.ViewGroup;

import com.birbit.android.databinding.library.DataBinder;
import com.birbit.android.databinding.library.IViewDataBinder;

abstract public class DataBoundAdapter<T extends IViewDataBinder>
        extends RecyclerView.Adapter<DataBoundAdapter.DataBoundViewHolder<T>> {
    final int mLayoutId;
    final Class<T> mBinderInterface;
    public DataBoundAdapter(int mLayoutId, Class<T> mBinderInterface) {
        this.mLayoutId = mLayoutId;
        this.mBinderInterface = mBinderInterface;
    }

    @Override
    public DataBoundAdapter.DataBoundViewHolder<T> onCreateViewHolder(ViewGroup viewGroup, int type) {
        T binder = DataBinder.createBinder(mBinderInterface, viewGroup.getContext(), mLayoutId, viewGroup);
        return new DataBoundViewHolder<T>(binder);
    }

    static class DataBoundViewHolder<T extends IViewDataBinder> extends RecyclerView.ViewHolder {
        public final T dataBinder;
        public DataBoundViewHolder(T mViewBinder) {
            super(mViewBinder.getRoot());
            this.dataBinder = mViewBinder;
        }
    }
}
